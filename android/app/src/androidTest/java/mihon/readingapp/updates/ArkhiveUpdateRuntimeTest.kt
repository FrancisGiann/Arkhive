package mihon.readingapp.updates

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ArkhiveUpdateRuntimeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun checkUsesOnlyThePinnedReleaseEndpointAndReportsNoNewerRelease() = runBlocking {
        val service = ArkhiveUpdateService(context, ReleaseTransport(releases(context.packageVersionName())))
        val result = service.check(includePreviews = true)
        assertTrue("An equal-version release is not offered as an update", result.isEmpty())
        assertEquals("The updater reads the version installed by PackageManager", context.packageVersionName(), service.installedVersionName())
    }

    @Test
    fun offlineCheckFailsClearlyAndNeverFallsBackToAnotherHost() = runBlocking {
        val transport = object : ArkhiveUpdateTransport {
            override suspend fun get(url: String, maxBytes: Int): ByteArray {
                assertEquals("https://api.github.com/repos/FrancisGiann/Arkhive/releases?per_page=30", url)
                throw IOException("fixture offline")
            }
            override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long =
                error("An offline release check must not start a download")
        }
        val failure = runCatching { ArkhiveUpdateService(context, transport).check(includePreviews = true) }.exceptionOrNull()
        assertTrue("Offline state remains an explicit failure", failure is IOException)
        assertEquals("fixture offline", failure?.message)
    }

    @Test
    fun cancelingAnInFlightApkBodyReadDeletesItsPartialFile() = runBlocking {
        val started = CompletableDeferred<File>()
        val release = requireNotNull(ArkhiveUpdatePolicy.parseReleases("[${releaseJson("0.4.2", "0".repeat(64), 128)}]", "0.4.1", true).firstOrNull())
        val transport = object : ArkhiveUpdateTransport {
            override suspend fun get(url: String, maxBytes: Int): ByteArray = error("The fixture release carries its digest")
            override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long {
                destination.parentFile?.mkdirs()
                destination.writeBytes(ByteArray(32) { 7 })
                started.complete(destination)
                awaitCancellation()
            }
        }
        val operation = launch { runCatching { ArkhiveUpdateService(context, transport).prepare(release) { _, _ -> } } }
        val partial = started.await()
        assertTrue("The controlled response has begun writing a partial file", partial.exists())
        operation.cancelAndJoin()
        assertFalse("Cancellation removes partial update bytes", partial.exists())
    }

    @Test
    fun anInvalidApkBodyFailsWithoutRetainingPartialOrReadyFiles() = runBlocking {
        val bytes = ByteArray(128) { 9 }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = ArkhiveUpdatePolicy.parseReleases("[${releaseJson("0.4.2", digest, bytes.size.toLong())}]", "0.4.1", true).single()
        val directory = File(context.cacheDir, "arkhive-update")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        var partial: File? = null
        val transport = object : ArkhiveUpdateTransport {
            override suspend fun get(url: String, maxBytes: Int): ByteArray = error("The fixture carries its digest")
            override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long {
                assertEquals(release.apkUrl, url)
                partial = destination
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
                progress(bytes.size.toLong(), bytes.size.toLong())
                return bytes.size.toLong()
            }
        }
        val failure = runCatching { ArkhiveUpdateService(context, transport).prepare(release) { _, _ -> } }.exceptionOrNull()
        assertTrue("A matching checksum alone must not make non-APK bytes installable", failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("not a readable Android APK"))
        assertFalse("Failed verification removes partial bytes", requireNotNull(partial).exists())
        assertEquals("Failed verification leaves no ready APK", before, directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    private fun releaseJson(version: String, hash: String, size: Long): String =
        """{"draft":false,"tag_name":"$version","prerelease":false,"name":"Arkhive $version","body":"Fixture update","assets":[{"name":"Arkhive-$version.apk","size":$size,"digest":"sha256:$hash","browser_download_url":"https://github.com/FrancisGiann/Arkhive/releases/download/$version/Arkhive-$version.apk"}]}"""

    private fun releases(installedVersionName: String): ByteArray {
        val installed = requireNotNull(ReleaseVersion.parseInstalled(installedVersionName))
        return "[${releaseJson(installed.normalized(), "0".repeat(64), 1024)}]".toByteArray()
    }

    private fun Context.packageVersionName(): String {
        @Suppress("DEPRECATION")
        return requireNotNull(packageManager.getPackageInfo(packageName, 0).versionName)
    }

    private class ReleaseTransport(private val payload: ByteArray) : ArkhiveUpdateTransport {
        override suspend fun get(url: String, maxBytes: Int): ByteArray {
            assertEquals("https://api.github.com/repos/FrancisGiann/Arkhive/releases?per_page=30", url)
            assertTrue(maxBytes in 1..(2 * 1024 * 1024))
            return payload
        }
        override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long =
            error("The no-update case must not download an APK")
    }
}
