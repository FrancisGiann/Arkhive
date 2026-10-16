package mihon.readingapp.compat

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only on an isolated ReadingApp emulator. Loads only APKs built from compat-fixtures/extension. */
@RunWith(AndroidJUnit4::class)
class ControlledExtensionRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun legacy14ArchiveNeedsExplicitTrustThenRunsRxBridge() = runBlocking {
        withFixture("legacy") { fixture ->
            assertUntrusted(fixture.pkgName)
            trustFixture(fixture)
            assertContract(requireLoaded(fixture.pkgName), 1.4)
        }
    }

    @Test
    fun modern16ArchiveNeedsExplicitTrustThenRunsSuspendContract() = runBlocking {
        withFixture("modern") { fixture ->
            assertUntrusted(fixture.pkgName)
            trustFixture(fixture)
            val loaded = requireLoaded(fixture.pkgName)
            assertContract(loaded, 1.6)
            val preferences = context.appGraph.sourcePreferences.trustedExtensions
            preferences.set(preferences.get().filterNot { it.startsWith("${fixture.pkgName}:") }.toSet())
            assertUntrusted(fixture.pkgName)
        }
    }

    @Test
    fun unsupported15IsRejectedBeforeExecutingSourceFactory() = runBlocking {
        withFixture("unsupported") { fixture ->
            val unloaded = ExtensionLoader.loadExtensionFromPkgName(context, fixture.pkgName) as Extension.NotLoaded
            assertEquals(Extension.NotLoaded.Reason.UnsupportedLibVersion, unloaded.reason)
            assertEquals(1.5, unloaded.libVersion!!, 0.0)
        }
    }

    @Test
    fun signedTrustedArchiveWithMissingClassIsReportedMalformed() = runBlocking {
        withFixture("malformed") { fixture ->
            trustFixture(fixture)
            val unloaded = ExtensionLoader.loadExtensionFromPkgName(context, fixture.pkgName) as Extension.NotLoaded
            assertEquals(Extension.NotLoaded.Reason.Malformed, unloaded.reason)
        }
    }

    private suspend fun assertUntrusted(pkgName: String) {
        val unloaded = ExtensionLoader.loadExtensionFromPkgName(context, pkgName) as Extension.NotLoaded
        assertTrue(unloaded.reason is Extension.NotLoaded.Reason.Untrusted)
    }

    private fun trustFixture(fixture: Fixture) {
        context.appGraph.trustExtension.trust(fixture.pkgName, fixture.versionCode, fixture.signature)
    }

    private suspend fun requireLoaded(pkgName: String): Extension.Loaded {
        val result = requireNotNull(ExtensionLoader.loadExtensionFromPkgName(context, pkgName))
        assertTrue("Controlled fixture did not load: $result", result is Extension.Loaded)
        return result as Extension.Loaded
    }

    private suspend fun assertContract(loaded: Extension.Loaded, expectedVersion: Double) {
        assertEquals(expectedVersion, loaded.libVersion, 0.0)
        assertFalse(loaded.isShared)
        assertEquals(1, loaded.sources.size)
        val source = loaded.sources.single()
        // Proves the implementation came from the controlled APK classloader, not a test's in-memory source.
        assertNotSame(context.classLoader, source.javaClass.classLoader)
        val manga = source.getPopularManga(1).mangas.single()
        assertEquals("Original fixture", manga.title)
        assertTrue(source.getPopularManga(2).mangas.isEmpty())
        assertTrue(source.getSearchManga(1, "absent", FilterList()).mangas.isEmpty())
        val update = source.getMangaUpdate(manga, emptyList(), true, true)
        assertEquals(1, update.chapters.size)
        val pages = source.getPageList(update.chapters.single())
        assertEquals(listOf(7, 2), pages.map { it.index })
        assertTrue(pages.all { it.imageUrl!!.startsWith("https://fixtures.readingapp.invalid/") })
        val unchanged = source.getMangaUpdate(manga, update.chapters, false, false)
        assertEquals(manga.url, unchanged.manga.url)
        assertEquals(update.chapters, unchanged.chapters)
    }

    private suspend fun withFixture(flavor: String, block: suspend (Fixture) -> Unit) {
        check("readingapp" in context.packageName.lowercase()) { "Never run against stock Mihon or user data" }
        val directory = File(context.filesDir, "exts").apply { check(mkdirs() || isDirectory) }
        val pkgName = "mihon.readingapp.compat.fixture.$flavor"
        val target = File(directory, "$pkgName.ext")
        check(!target.exists()) { "Fixture already exists; use a clean emulator" }
        val trustedPreferences = context.appGraph.sourcePreferences.trustedExtensions
        val previousTrust = trustedPreferences.get()
        try {
            instrumentation.context.assets.open("readingapp/compat/$flavor.apk").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            check(target.setReadOnly()) { "Android dynamic APK input must be read-only before loading" }
            val archive = requireNotNull(ExtensionLoader.getArchivePackageInfo(context, target))
            assertEquals(pkgName, archive.packageName)
            val signatures = requireNotNull(ExtensionLoader.getSignatures(archive))
            assertTrue("Built fixture must be signed", signatures.isNotEmpty())
            @Suppress("DEPRECATION")
            val permissions = requireNotNull(context.packageManager.getPackageArchiveInfo(target.path, PackageManager.GET_PERMISSIONS))
            assertTrue("Controlled fixture must request no permissions", permissions.requestedPermissions.orEmpty().isEmpty())
            assertNotNull(archive.applicationInfo)
            block(Fixture(pkgName, PackageInfoCompat.getLongVersionCode(archive), signatures.last()))
        } finally {
            trustedPreferences.set(previousTrust)
            target.setWritable(true)
            check(target.delete() || !target.exists())
        }
    }

    private data class Fixture(val pkgName: String, val versionCode: Long, val signature: String)
}
