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
            assertTrue("Series tools remain reachable in the hero", device.hasObject(By.text("Series tools")))
            assertTrue("The accessible title has rendered bounds", requireNotNull(titleNode).visibleBounds.height() > 0)
            android.util.Log.i("NovelDetailLayout", "HERO_READY")
            Thread.sleep(5_000) // Allows a host screencap of the real rendered state.
            assertTrue("Capture the rendered long-title hero", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-long-title-1.3x.png")))

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
            assertTrue("Capture the rendered chapter panel", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-chapters-1.3x.png")))

            val pageOne = By.desc("Page 1 of 3. Find or jump to a chapter or search.")
            for (attempt in 0 until 8) {
                if (device.hasObject(pageOne)) break
                device.swipe(device.displayWidth / 2, (device.displayHeight * .78).toInt(), device.displayWidth / 2,
                    (device.displayHeight * .58).toInt(), 20)
            }
            assertNotNull("More than 100 chapters show a page indicator and jump action", device.wait(Until.findObject(pageOne), 5_000))
            assertTrue("Capture the compact pager at narrow width and large font scale", device.takeScreenshot(File(context.cacheDir, "qa-novel-detail-pager-320dp-1.3x.png")))
            assertTrue("The current or next unread action remains available", device.hasObject(By.text("Next unread")))
            val previousButton = requireNotNull(device.findObject(By.desc("Previous page")))
            val nextButton = requireNotNull(device.findObject(By.desc("Next page")))
            val densityScale = context.resources.displayMetrics.density
            val viewportWidth = (320 * densityScale).toInt()
            val viewportLeft = (device.displayWidth - viewportWidth) / 2
            val minTouchTarget = (48 * densityScale).toInt()
            assertTrue("Previous page target is at least 48dp", previousButton.visibleBounds.width() >= minTouchTarget)
            assertTrue("Next page target is at least 48dp", nextButton.visibleBounds.width() >= minTouchTarget)
            assertTrue("Previous page stays inside the 320dp viewport", previousButton.visibleBounds.left >= viewportLeft)
            assertTrue("Next page stays inside the 320dp viewport", nextButton.visibleBounds.right <= viewportLeft + viewportWidth)
            nextButton.click()
            val pageTwo = By.desc("Page 2 of 3. Find or jump to a chapter or search.")
            assertNotNull("Next advances to page two", device.wait(Until.findObject(pageTwo), 5_000))
            assertTrue("The next bounded chapter slice is visible", device.hasObject(By.text("Chapter 101")))

            requireNotNull(device.findObject(pageTwo)).click()
            val searchFields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("Page indicator opens search and jump", searchFields.size >= 2)
            searchFields[0].text = "title-does-not-exist"
            device.pressBack()
            click("Done")
            val noMatches = By.desc("No matches. Search or jump to a chapter.")
            assertNotNull("No-match state keeps search and jump reachable", device.wait(Until.findObject(noMatches), 5_000))
            requireNotNull(device.findObject(noMatches)).click()
            assertNotNull("The no-match action reopens the search dialog", device.wait(Until.findObject(By.text("Find or jump to chapter")), 5_000))
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
