package mihon.readingapp.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import androidx.test.uiautomator.UiObject2
import mihon.readingapp.settings.InkSettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicitly staged signed-upgrade journey; root's ordinary suite collector excludes this class. */
@RunWith(AndroidJUnit4::class)
class ArkhiveUpdateStagedRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    /** Root stages the frozen final-signer APK here after building the code-7 QA receiver. */
    private fun stagedFinalApk() = File(requireNotNull(context.getExternalFilesDir(null)), "arkhive-qa-updates/Arkhive-0.4.1.apk")

    @Test
    fun stagedSignedApkIsDownloadedAndVerifiedBeforeTheAndroidPermissionGate() = runBlocking {
        val artifact = stagedFinalApk()
        assertTrue("Stage the frozen final 0.4.1 APK at ${artifact.absolutePath}", artifact.isFile && artifact.length() in 1..ArkhiveUpdatePolicy.MAX_APK_BYTES)
        val transport = StagedArtifactTransport(artifact)
        val service = ArkhiveUpdateService(context, transport)
        val installed = service.installedPackage()
        assertTrue("The staged receiver must be lower versionCode than the frozen 0.4.1 update", installed.versionCode < 8L)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val candidateInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(artifact.absolutePath, flags))
        val candidateVersion = PackageInfoCompat.getLongVersionCode(candidateInfo)
        assertEquals(context.packageName, candidateInfo.packageName)
        assertTrue("The final signed APK is strictly newer than the receiver", candidateVersion > installed.versionCode)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        InkSettingsTheme {
                            CompositionLocalProvider(LocalArkhiveUpdateService provides service) {
                                Navigator(screen = ArkhiveUpdateScreen()) { navigator -> DefaultNavigatorScreenTransition(navigator) }
                            }
                        }
                    }
                })
            }
            requireNotNull(device.wait(Until.findObject(By.text("Check for updates")), 10_000)).click()
            assertNotNull("The authored GitHub fixture lists the exact release asset", device.wait(Until.findObject(By.text("Arkhive-0.4.1.apk")), 10_000))
            requireNotNull(visibleText("Download and verify")).click()
            assertNotNull("The actual screen reaches the post-verification state", visibleText("Verified Arkhive-0.4.1.apk"))
            val nextAction = if (service.canInstallUnknownSources()) "Install verified update" else "Open Android install permission"
            assertNotNull("Installer handoff remains explicitly gated by Android's own special-access setting",
                visibleText(nextAction))
            val qa = File(requireNotNull(context.getExternalFilesDir(null)), "arkhive-qa").apply { mkdirs() }
            assertTrue("Capture the real updater review/verification state", device.takeScreenshot(File(qa, "qa-arkhive-update-verified.png")))
            assertEquals("Only the pinned API and exact public release asset are requested",
                listOf(transport.apiUrl, transport.assetUrl), transport.requests)
        } finally {
            scenario.close()
        }
    }

    private suspend fun visibleText(text: String): UiObject2 = withTimeout(35_000) {
        while (true) {
            device.findObject(By.text(text))?.let { return@withTimeout it }
            device.swipe(device.displayWidth / 2, (device.displayHeight * .80f).toInt(),
                device.displayWidth / 2, (device.displayHeight * .57f).toInt(), 24)
            delay(150)
        }
        error("unreachable")
    }

    private class StagedArtifactTransport(private val artifact: File) : ArkhiveUpdateTransport {
        val requests = mutableListOf<String>()
        val expectedDigest = artifact.sha256()
        private val version = "0.4.1"
        private val asset = "Arkhive-$version.apk"
        val assetUrl = "https://github.com/FrancisGiann/Arkhive/releases/download/$version/$asset"
        val apiUrl = "https://api.github.com/repos/FrancisGiann/Arkhive/releases?per_page=30"

        override suspend fun get(url: String, maxBytes: Int): ByteArray {
            requests += url
            assertEquals(apiUrl, url)
            assertTrue(maxBytes in 1..(2 * 1024 * 1024))
            return """[{"draft":false,"tag_name":"$version","prerelease":true,"name":"Arkhive 0.4.1 preview","body":"Self-authored signed-upgrade QA fixture.","assets":[{"name":"$asset","size":${artifact.length()},"digest":"sha256:$expectedDigest","browser_download_url":"$assetUrl"}]}]""".toByteArray()
        }

        override suspend fun download(url: String, destination: File, maxBytes: Long, progress: (Long, Long) -> Unit): Long {
            requests += url
            assertEquals(assetUrl, url)
            require(artifact.length() <= maxBytes)
            destination.parentFile?.mkdirs()
            return artifact.inputStream().buffered().use { input -> destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); total += count; progress(total, artifact.length()) }
                output.flush()
                total
            } }
        }

        private fun File.sha256(): String = inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
