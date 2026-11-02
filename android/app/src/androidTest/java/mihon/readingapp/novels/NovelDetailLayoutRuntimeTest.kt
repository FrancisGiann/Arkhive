package mihon.readingapp.novels

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.app.di.appGraph
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NovelDetailLayoutRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun paginationControlsFitNarrowLargeTextAndKeepJumpAvailableAfterNoMatches() = kotlinx.coroutines.runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val uiVariant = InstrumentationRegistry.getArguments().getString("arkhive_ui_variant", "revised")
        assertTrue("Pass arkhive_ui_variant=baseline or revised", uiVariant in setOf("baseline", "revised"))
        val sourceId = "qa.novel-detail.$suffix"
        val path = "detail-$suffix"
        val title = "The Mercenary’s Extraordinary Machinations Across the Kingdom Beyond the Forgotten Mountain Citadel and the Crimson Sea"
        val source = object : NovelSource {
            override val id = sourceId
            override val name = "Detail layout fixture"
            override val site = "https://fixture.invalid"
            override suspend fun catalog(page: Int, search: String, genre: String?) =
                NovelCatalogPage(emptyList(), null, "Authored detail layout fixture")
            override suspend fun detail(path: String) = SourceNovel(
                path = path,
                name = title,
                author = "Fixture author",
                chapters = (1..250).map { NovelChapter("chapter-$it", "Chapter $it") },
                provenanceUrl = site,
                rights = "Authored instrumentation fixture",
            )
            override suspend fun chapter(path: String) = ""
        }
        val repository = context.appGraph.novelRepository
        val wasPaused = repository.state.value.paused
        val wasIncognito = context.appGraph.basePreferences.incognitoMode.get()
        var novelId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        repository.sources.fixtures[sourceId] = source
        try {
            repository.pause(true)
            context.appGraph.basePreferences.incognitoMode.set(false)
            val novel = repository.load(sourceId, path)
            novelId = novel.id
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalNovelSources provides repository.sources,
                            LocalDensity provides Density(density.density, fontScale = 1.3f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(320.dp).fillMaxHeight()) {
                                    Navigator(screen = NovelDetailScreen(sourceId, path)) { navigator ->
                                        DefaultNavigatorScreenTransition(navigator)
                                    }
                                }
                            }
                        }
                    }
                })
            }

            val titleNode = device.wait(Until.findObject(By.desc(title)), 12_000)
            assertNotNull("The full source title is exposed as an accessible label", titleNode)
            assertTrue("Library membership remains reachable in the shared hero", device.hasObject(By.textContains("Add to library")))
            assertTrue(
                "Series actions remain reachable in the hero",
                device.hasObject(By.text(if (uiVariant == "baseline") "Series tools" else "More")),
            )
            assertTrue(
                "Batch downloads remain reachable",
                device.hasObject(if (uiVariant == "baseline") By.text("Download") else By.desc("Download chapters")),
            )
            assertTrue("The accessible title has rendered bounds", requireNotNull(titleNode).visibleBounds.height() > 0)
            val toolbarSearch = device.findObject(By.desc("Find chapters"))
            if (uiVariant == "revised") {
                assertNotNull("The compact toolbar exposes search in the redesigned detail", toolbarSearch)
            }
            toolbarSearch?.let { search ->
                search.click()
                assertNotNull("Toolbar search opens its screen-owned dialog from the hero", device.wait(Until.findObject(By.text("Find or jump to chapter")), 5_000))
                click("Done")
            }
            android.util.Log.i("NovelDetailLayout", "HERO_READY")
            Thread.sleep(5_000) // Allows a host screencap of the real rendered state.
            assertTrue("Capture the rendered long-title hero", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-long-title-1.3x-$uiVariant.png")))

            val chaptersHeading = By.text("CHAPTERS") // InkKicker renders headings in uppercase.
            for (attempt in 0 until 8) {
                if (device.hasObject(chaptersHeading) && device.hasObject(By.text("Start reading"))) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * .76).toInt(), device.displayWidth / 2,
                    (device.displayHeight * .65).toInt(), 20)
            }
            assertNotNull("Scrolling the same detail list reaches the chapter panel", device.wait(Until.findObject(chaptersHeading), 5_000))
            assertNotNull("The primary reading action remains reachable after the long hero", device.wait(Until.findObject(By.text("Start reading")), 5_000))
            android.util.Log.i("NovelDetailLayout", "CHAPTERS_READY")
            Thread.sleep(5_000) // Allows a host screencap of the real rendered state.
            assertTrue("Capture the rendered chapter panel", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-chapters-1.3x-$uiVariant.png")))

            val pageOne = By.desc(
                if (uiVariant == "revised") {
                    "Page 1 of 3. Chapters 1 to 100 of 250. Find or jump to a chapter or search."
                } else {
                    "Page 1 of 3. Find or jump to a chapter or search."
                },
            )
            for (attempt in 0 until 8) {
                if (device.hasObject(pageOne)) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * .78).toInt(), device.displayWidth / 2,
                    (device.displayHeight * .58).toInt(), 20)
            }
            assertNotNull("More than 100 chapters show a page indicator and jump action", device.wait(Until.findObject(pageOne), 5_000))
            assertTrue("Capture the compact pager at narrow width and large font scale", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-pager-320dp-1.3x-$uiVariant.png")))
            if (uiVariant == "revised" && device.hasObject(By.desc("More chapter actions"))) {
                requireNotNull(device.findObject(By.desc("More chapter actions"))).click()
                assertNotNull("Narrow layout keeps Next unread in the labeled actions menu", device.findObject(By.text("Next unread")))
                device.pressBack()
            } else {
                assertTrue("The next unread action remains available", device.hasObject(By.text("Next unread")))
            }
            val previousButton = requireNotNull(device.findObject(By.desc("Previous page")))
            val nextButton = requireNotNull(device.findObject(By.desc("Next page")))
            val densityScale = context.resources.displayMetrics.density
            val viewportWidth = (320 * densityScale).toInt()
            val viewportLeft = (device.displayWidth - viewportWidth) / 2
            val minTouchTarget = (48 * densityScale).toInt()
            assertTrue("Previous page target is at least 48dp", previousButton.visibleBounds.width() >= minTouchTarget)
            assertTrue("Next page target is at least 48dp", nextButton.visibleBounds.width() >= minTouchTarget)
            assertTrue("First page disables Previous", !previousButton.isEnabled)
            assertTrue("Previous page stays inside the 320dp viewport", previousButton.visibleBounds.left >= viewportLeft)
            assertTrue("Next page stays inside the 320dp viewport", nextButton.visibleBounds.right <= viewportLeft + viewportWidth)
            nextButton.click()
            val pageTwo = By.desc(
                if (uiVariant == "revised") {
                    "Page 2 of 3. Chapters 101 to 200 of 250. Find or jump to a chapter or search."
                } else {
                    "Page 2 of 3. Find or jump to a chapter or search."
                },
            )
            assertNotNull("Next advances to page two", device.wait(Until.findObject(pageTwo), 5_000))
            assertTrue("The next bounded chapter slice is visible", device.hasObject(By.text("Chapter 101")))
            nextButton.click()
            val pageThree = By.desc(
                if (uiVariant == "revised") {
                    "Page 3 of 3. Chapters 201 to 250 of 250. Find or jump to a chapter or search."
                } else {
                    "Page 3 of 3. Find or jump to a chapter or search."
                },
            )
            assertNotNull("Next reaches the last page", device.wait(Until.findObject(pageThree), 5_000))
            assertTrue("Last page disables Next", !requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)
            requireNotNull(device.findObject(By.desc("Previous page"))).click()
            assertNotNull("Previous returns to page two", device.wait(Until.findObject(pageTwo), 5_000))

            requireNotNull(device.findObject(pageTwo)).click()
            val searchFields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("Page indicator opens search and jump", searchFields.size >= 2)
            searchFields[0].text = "Chapter 250"
            device.pressBack()
            click("Done")
            val singlePage = By.desc(
                if (uiVariant == "revised") {
                    "Page 1 of 1. Chapters 1 to 1 of 1. Search filter: Chapter 250. Find or jump to a chapter or search."
                } else {
                    "Page 1 of 1. Find or jump to a chapter or search."
                },
            )
            assertNotNull("Single matching chapter uses a single-page state", device.wait(Until.findObject(singlePage), 5_000))
            assertTrue("Single-page search disables Previous", !requireNotNull(device.findObject(By.desc("Previous page"))).isEnabled)
            assertTrue("Single-page search disables Next", !requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)
            requireNotNull(device.findObject(singlePage)).click()
            val emptySearchFields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
            emptySearchFields[0].text = "title-does-not-exist"
            device.pressBack()
            click("Done")
            val noMatches = By.desc(
                if (uiVariant == "revised") {
                    "No matches for title-does-not-exist. Search or jump to a chapter."
                } else {
                    "No matches. Search or jump to a chapter."
                },
            )
            assertNotNull("No-match state keeps search and jump reachable", device.wait(Until.findObject(noMatches), 5_000))
            requireNotNull(device.findObject(noMatches)).click()
            assertNotNull("The no-match action reopens the search dialog", device.wait(Until.findObject(By.text("Find or jump to chapter")), 5_000))

            requireNotNull(scenario).onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalNovelSources provides repository.sources,
                            LocalDensity provides Density(density.density, fontScale = 2f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(320.dp).fillMaxHeight()) {
                                    Navigator(screen = NovelDetailScreen(sourceId, path)) { navigator ->
                                        DefaultNavigatorScreenTransition(navigator)
                                    }
                                }
                            }
                        }
                    }
                })
            }
            val largeFontTitle = device.wait(Until.findObject(By.desc(title)), 12_000)
            assertNotNull("Novel title remains accessible at 320dp/2.0x", largeFontTitle)
            assertTrue("Capture the 2.0x responsive novel hero", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-long-title-2.0x-$uiVariant.png")))
            val pageOneLargeFont = By.desc(
                if (uiVariant == "revised") {
                    "Page 1 of 3. Chapters 1 to 100 of 250. Find or jump to a chapter or search."
                } else {
                    "Page 1 of 3. Find or jump to a chapter or search."
                },
            )
            for (attempt in 0 until 18) {
                if (device.hasObject(pageOneLargeFont)) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * .78).toInt(), device.displayWidth / 2,
                    (device.displayHeight * .57).toInt(), 20)
            }
            val largeFontPager = requireNotNull(device.wait(Until.findObject(pageOneLargeFont), 5_000))
            val largeFontDensity = context.resources.displayMetrics.density
            assertTrue("2.0x page jump target remains at least 48dp", largeFontPager.visibleBounds.height() >= (48 * largeFontDensity).toInt())
            assertTrue("2.0x pager stays inside 320dp width", largeFontPager.visibleBounds.left >= (device.displayWidth - (320 * largeFontDensity).toInt()) / 2)
            assertTrue("Capture the novel pager at 320dp/2.0x", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-pager-320dp-2.0x-$uiVariant.png")))
        } finally {
            scenario?.close()
            novelId?.let { repository.remove(it) }
            repository.sources.fixtures.remove(sourceId)
            repository.pause(wasPaused)
            context.appGraph.basePreferences.incognitoMode.set(wasIncognito)
        }
    }

    private fun click(label: String) {
        val target = requireNotNull(device.wait(Until.findObject(By.text(label)), 5_000)) { "Missing UI control: $label" }
        target.click()
    }
}
