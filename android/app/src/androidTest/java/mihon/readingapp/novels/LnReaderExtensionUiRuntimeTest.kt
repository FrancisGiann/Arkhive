package mihon.readingapp.novels

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.main.MainActivity
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.readingapp.settings.InkSettingsTheme
import mihon.readingapp.novels.lnreader.LnReaderExtensionManager
import mihon.readingapp.novels.lnreader.LnReaderSandboxRuntime
import mihon.readingapp.novels.lnreader.LnReaderHostFetch
import mihon.readingapp.novels.lnreader.LnReaderHttpRequest
import mihon.readingapp.novels.lnreader.LnReaderHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Drives the real Compose import/trust/source/detail/reader UI with an authored-only transport. */
@RunWith(AndroidJUnit4::class)
class LnReaderExtensionUiRuntimeTest {
    private data class RenderedReaderSnapshot(
        val matches: Int,
        val shown: Boolean,
        val width: Int,
        val height: Int,
        val visibleWidth: Int,
        val visibleHeight: Int,
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test
    fun reviewedExtensionOriginApprovalFlowsThroughCompose() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val repositoryUrl = "https://fixture.invalid/ui-$suffix/manifest.json"
        val sourceDirectory = File(context.cacheDir, "lnreader-ui-manager-$suffix")
        val sourceContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = sourceDirectory
        }
        val transport = UiFixtureTransport(repositoryUrl)
        val manager = LnReaderExtensionManager(sourceContext, transport, LnReaderSandboxRuntime(context, transport))
        val uiSources = NovelSources(context.appGraph.networkHelper, manager)
        val appSources = context.appGraph.novelRepository.sources
        val priorIncognito = context.appGraph.basePreferences.incognitoMode.get()
        context.appGraph.basePreferences.incognitoMode.set(false)
        val testNavigator = AtomicReference<Navigator?>()
        var installedSourceId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        InkSettingsTheme {
                            androidx.compose.runtime.CompositionLocalProvider(LocalNovelSources provides uiSources) {
                                Navigator(screen = UiNovelSourceListScreen()) { navigator ->
                                    testNavigator.set(navigator)
                                    DefaultNavigatorScreenTransition(navigator)
                                }
                            }
                        }
                    }
                })
            }

            waitFor("The authored source-list host is ready") { device.hasObject(By.text("ONLINE TEXT SOURCES")) }
            scenario.onActivity { requireNotNull(testNavigator.get()).push(NovelExtensionsScreen()) }
            waitFor("The test host opens the actual extension management screen") { device.hasObject(By.text("Add and refresh")) }
            val repositoryInput = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000))
            repositoryInput.text = repositoryUrl
            closeSoftKeyboard(requireNotNull(scenario))
            click("Add and refresh")
            scrollUntilVisible("Authored UI extension")
            assertEquals("Repository refresh lists metadata without executing plugin code.", 0, transport.pluginExecutionRequests.get())
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-metadata.png")))

            scrollUntilVisible("Review")
            click("Review")
            waitFor("First bundle review is open") { device.hasObject(By.text("Trust and install")) }
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-review.png")))
            click("Cancel")
            waitFor("Cancelled review closes") { !device.hasObject(By.text("Trust and install")) }
            assertEquals("Cancelling review never executes plugin code.", 0, transport.pluginExecutionRequests.get())

            click("Review")
            waitFor("Second bundle review is open") { device.hasObject(By.text("Trust and install")) }
            click("Trust and install")
            waitFor("Trust result dialog appears") { device.hasObject(By.text("Done")) }
            val installed = manager.snapshot().installedPlugins.single()
            installedSourceId = installed.sourceId
            assertTrue(installed.enabled)
            assertEquals("Trust installs the reviewed bundle but does not eagerly run it.", 0, transport.pluginExecutionRequests.get())
            val fixtureAdapter = uiSources.get(installed.sourceId)
            // The production NovelRepository is intentionally retained. Its controlled fixture
            // map resolves only the same already-reviewed adapter when the UI opens this novel.
            appSources.fixtures[installed.sourceId] = fixtureAdapter
            click("Done")
            scrollUntilVisible("Review update")
            waitFor("Installed extension review action is available") { device.hasObject(By.text("Review update")) }
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-installed-actions.png")))
            scenario.onActivity { requireNotNull(testNavigator.get()).push(NovelCatalogScreen(installed.sourceId)) }
            waitFor("Authored catalog title is rendered") { device.hasObject(By.text("UI Lantern Novel")) }
            assertTrue("Opening the source runs only the explicitly trusted plugin operation.", transport.pluginExecutionRequests.get() > 0)
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-catalog.png")))

            val searchInput = requireNotNull(device.findObject(By.clazz("android.widget.EditText")))
            searchInput.text = "lantern"
            closeSoftKeyboard(requireNotNull(scenario))
            click("Search")
            waitFor("Catalog search action renders its authored result") { device.hasObject(By.text("UI Search Lantern")) }
            click("UI Search Lantern")
            waitFor("Novel detail offers a real read action") { device.hasObject(By.text("Start reading")) }
            waitFor("A blocked cover exposes its exact origin") {
                device.hasObject(By.textContains("This cover needs the exact origin https://images.fixture.invalid:443"))
            }
            click("Review extension origins")
            waitFor("The review route focuses the source that returned this cover") {
                device.hasObject(By.text("Review update")) && !device.hasObject(By.text("Find an extension"))
            }
            click("Review update")
            waitFor("The extension review offers the observed cover host") {
                device.hasObject(By.text("Add images.fixture.invalid:443"))
            }
            val suggestedCoverChip = requireNotNull(
                requireNotNull(device.findObject(By.text("Add images.fixture.invalid:443"))).parent,
            )
            assertTrue("The observed origin is an explicit checkable chip.", suggestedCoverChip.isCheckable)
            assertFalse("The observed origin is not preselected.", suggestedCoverChip.isChecked)
            suggestedCoverChip.click()
            waitFor("The host selection is visibly checked") {
                requireNotNull(
                    requireNotNull(device.findObject(By.text("Add images.fixture.invalid:443"))).parent,
                ).isChecked
            }
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-origin-review-selected.png")))
            click("Cancel")
            waitFor("Cancelling origin review closes its trust dialog") { !device.hasObject(By.text("Trust and update")) }
            assertFalse(
                "Cancel does not persist the observed cover origin.",
                "https://images.fixture.invalid:443" in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins,
            )
            device.pressBack()
            waitFor("Returning to the novel detail retries the blocked cover") {
                device.hasObject(By.textContains("This cover needs the exact origin https://images.fixture.invalid:443"))
            }

            click("Review extension origins")
            click("Review update")
            waitFor("The focused extension update review reopens") { device.hasObject(By.text("Trust and update")) }
            val additionalOriginInput = requireNotNull(device.findObject(By.clazz("android.widget.EditText")))
            additionalOriginInput.text = "http://images.fixture.invalid/assets/cover.png?size=small"
            scrollUntilVisible("HTTP origins cannot be trusted", contains = true, startYPercent = 75)
            waitFor("HTTP input shows the policy reason inline") {
                device.hasObject(By.textContains("HTTP origins cannot be trusted"))
            }
            assertFalse("Trust is disabled for HTTP input.", clickableAncestor("Trust and update").isEnabled)
            additionalOriginInput.text = "https://images.fixture.invalid/assets/cover.png?size=small,medium"
            scrollUntilVisible("• https://images.fixture.invalid:443", startYPercent = 75)
            waitFor("The review previews the exact normalized host and port") {
                device.hasObject(By.text("• https://images.fixture.invalid:443"))
            }
            assertTrue("Trust is enabled only after valid HTTPS input.", clickableAncestor("Trust and update").isEnabled)
            assertTrue(device.takeScreenshot(File(context.cacheDir, "lnreader-ui-origin-review-preview.png")))
            click("Trust and update")
            waitFor("The authored update was explicitly trusted") { device.hasObject(By.text("Done")) }
            assertTrue(
                "The URL path/query adds only its exact origin.",
                "https://images.fixture.invalid:443" in requireNotNull(manager.currentInstalled(installed.sourceId)).approvedOrigins,
            )
            click("Done")
            device.pressBack()
            waitFor("The cover loads after returning to its updated source") {
                transport.coverRequests.get() > 0 && !device.hasObject(By.textContains("This cover needs the exact origin https://images.fixture.invalid:443"))
            }
        } catch (failure: Throwable) {
            runCatching {
                val diagnostics = File(context.cacheDir, "lnreader-ui-failure-$suffix").apply { mkdirs() }
                device.dumpWindowHierarchy(File(diagnostics, "hierarchy.xml"))
                device.takeScreenshot(File(diagnostics, "screen.png"))
            }
            throw failure
        } finally {
            installedSourceId?.let { sourceId ->
                appSources.fixtures.remove(sourceId)
                runCatching { context.appGraph.novelRepository.remove(NovelRepository.novelId(sourceId, "ui-novel")) }
                runCatching { manager.uninstall(sourceId) }
            }
            scenario?.close()
            uiSources.closeForTest()
            sourceDirectory.deleteRecursively()
            context.appGraph.basePreferences.incognitoMode.set(priorIncognito)
        }
    }

    private fun closeSoftKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = activity.currentFocus?.windowToken ?: activity.window.decorView.windowToken
            manager.hideSoftInputFromWindow(token, 0)
            activity.currentFocus?.clearFocus()
        }
        instrumentation.waitForIdleSync()
    }

    private suspend fun waitFor(description: String, condition: () -> Boolean) {
        try {
            withTimeout(25_000) {
                while (!condition()) {
                    instrumentation.waitForIdleSync()
                    delay(100)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description", timeout)
        }
    }

    /** Searches rendered document text in the real, resumed NovelReaderActivity WebView.
     * At large system font sizes WebView's screenshot can contain visible lines while its
     * pruned accessibility subtree exposes no text nodes, so accessibility alone is not a
     * reliable readiness signal for this UI journey.
     */
    private suspend fun awaitRenderedReaderBody(expectedText: String): RenderedReaderSnapshot {
        var lastSnapshot: RenderedReaderSnapshot? = null
        try {
            return withTimeout(25_000) {
                while (true) {
                    val snapshot = readRenderedReaderSnapshot(expectedText)
                    if (snapshot != null) {
                        lastSnapshot = snapshot
                        if (snapshot.shown && snapshot.matches > 0 &&
                            snapshot.width > 0 && snapshot.height > 0 &&
                            snapshot.visibleWidth > 0 && snapshot.visibleHeight > 0
                        ) return@withTimeout snapshot
                    }
                    delay(100)
                }
                error("unreachable")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "The real reader WebView did not render '$expectedText'; lastSnapshot=$lastSnapshot",
                timeout,
            )
        }
    }

    private suspend fun readRenderedReaderSnapshot(expectedText: String): RenderedReaderSnapshot? {
        val result = CompletableDeferred<RenderedReaderSnapshot?>()
        var activeWeb: NovelReaderActivity.TextWebView? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NovelReaderActivity>()
                .lastOrNull()
            val web = activity?.let { findReaderWeb(it.window.decorView) }
            if (web == null) {
                result.complete(null)
                return@runOnMainSync
            }
            activeWeb = web

            val visibleRect = Rect()
            val hasVisibleRect = web.isShown && web.getGlobalVisibleRect(visibleRect)
            val shown = web.isShown && web.visibility == View.VISIBLE
            val width = web.width
            val height = web.height
            val visibleWidth = if (hasVisibleRect) visibleRect.width() else 0
            val visibleHeight = if (hasVisibleRect) visibleRect.height() else 0
            web.setFindListener { _, numberOfMatches, isDoneCounting ->
                if (isDoneCounting && !result.isCompleted) {
                    result.complete(
                        RenderedReaderSnapshot(
                            matches = numberOfMatches,
                            shown = shown,
                            width = width,
                            height = height,
                            visibleWidth = visibleWidth,
                            visibleHeight = visibleHeight,
                        ),
                    )
                }
            }
            web.findAllAsync(expectedText)
        }
        return try {
            result.await()
        } finally {
            // Timed-out readiness probes must not leave a listener or highlighted match on a
            // live reader. This also makes each retry independent at 200% text size.
            instrumentation.runOnMainSync {
                activeWeb?.let { web ->
                    web.setFindListener(null)
                    web.clearMatches()
                }
            }
        }
    }

    private fun findReaderWeb(view: View): NovelReaderActivity.TextWebView? {
        if (view is NovelReaderActivity.TextWebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findReaderWeb(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private suspend fun scrollUntilVisible(
        label: String,
        contains: Boolean = false,
        startYPercent: Int = 80,
    ) {
        val isVisible = {
            if (contains) device.hasObject(By.textContains(label)) else device.hasObject(By.text(label))
        }
        try {
            withTimeout(25_000) {
                while (!isVisible()) {
                    device.swipe(device.displayWidth / 2, device.displayHeight * startYPercent / 100, device.displayWidth / 2, device.displayHeight / 3, 20)
                    instrumentation.waitForIdleSync()
                    delay(250)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for UI text '$label' to become visible", timeout)
        }
    }

    private fun clickableAncestor(label: String) = run {
        var ancestor = requireNotNull(device.findObject(By.text(label))) { "Missing UI text: $label" }.parent
        while (ancestor != null && !ancestor.isClickable) ancestor = ancestor.parent
        requireNotNull(ancestor) { "UI text '$label' has no clickable control ancestor" }
    }

    private fun currentChapterPercent(): Int? {
        val label = device.findObject(By.text(Pattern.compile("^Chapter \\d{1,3}%$"))) ?: return null
        return label.text.removePrefix("Chapter ").removeSuffix("%").toIntOrNull()
    }

    private fun click(label: String) {
        val target = requireNotNull(device.wait(Until.findObject(By.text(label)), 10_000)) { "Missing UI control: $label" }
        target.click()
    }

    private class UiNovelSourceListScreen : Screen() {
        @androidx.compose.runtime.Composable
        override fun Content() = NovelSourceList()
    }

    private class UiFixtureTransport(private val repositoryUrl: String) : LnReaderHostFetch {
        val pluginExecutionRequests = AtomicInteger()
        val coverRequests = AtomicInteger()
        private val coverPng = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
            output.toByteArray()
        }
        private val manifest = """[
          {"id":"ui-authored-source","name":"Authored UI extension","site":"https://fixture.invalid/","lang":"en","version":"1.0","url":"https://fixture.invalid/ui-plugin.js"}
        ]""".toByteArray()
        private val bundle = UI_PLUGIN.toByteArray()

        override suspend fun fetch(request: LnReaderHttpRequest, allowedOrigins: Set<String>, maxBytes: Int): LnReaderHttpResponse {
            assertTrue("Only the synthetic reviewed origin is served.", "https://fixture.invalid:443" in allowedOrigins)
            val body = when {
                request.url == repositoryUrl -> manifest
                request.url.endsWith("/ui-plugin.js") -> bundle
                request.url.startsWith("https://images.fixture.invalid/") -> {
                    coverRequests.incrementAndGet()
                    coverPng
                }
                request.url.endsWith("/plugin-executed") -> {
                    pluginExecutionRequests.incrementAndGet()
                    "fixture execution confirmed".toByteArray()
                }
                else -> error("Unexpected authored UI fixture request: ${request.url}")
            }
            require(body.size <= maxBytes)
            val contentType = if (request.url.startsWith("https://images.fixture.invalid/")) "image/png" else "text/plain"
            return LnReaderHttpResponse(request.url, 200, "OK", mapOf("content-type" to contentType), body)
        }
    }

    private companion object {
        val UI_PLUGIN = """
            const { fetchApi } = require('@libs/fetch');
            module.exports.default = {
              id: 'ui-authored-source', name: 'Authored UI extension', site: 'https://fixture.invalid/', version: '1.0',
              async popularNovels(page, options) {
                if (page > 1) return [];
                await (await fetchApi('https://fixture.invalid/plugin-executed')).text();
                return [{ name: 'UI Lantern Novel', path: 'ui-novel' }];
              },
              async searchNovels(search, page) {
                if (page > 1) return [];
                await (await fetchApi('https://fixture.invalid/plugin-executed')).text();
                return [{ name: 'UI Search Lantern', path: 'ui-novel' }];
              },
              async parseNovel(path) {
                return { name: 'UI Lantern Novel', path, cover: 'https://images.fixture.invalid/cover.png', chapters: [
                  { name: 'UI Chapter One', path: 'ui-chapter', chapterNumber: 1 }
                ], totalPages: 1 };
              },
              resolveUrl(path) { return 'https://fixture.invalid/' + path; },
              async parseChapter(path) {
                return '<p>UI fixture chapter body: the small lantern keeper crossed the quiet bridge.</p>' +
                  Array.from({length: 48}, (_, i) => '<p>Authored offline-safe paragraph ' + i + ' describes the river, the village, and the evening light.</p>').join('');
              }
            };
        """.trimIndent()
    }
}
