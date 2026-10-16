package mihon.readingapp.novels

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
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

    @Test fun longTitleRemainsAccessibleAndChaptersStayReachableAtLargeFontScale() = kotlinx.coroutines.runBlocking {
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
                chapters = (1..18).map { NovelChapter("chapter-$it", "Chapter $it") },
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
                            Navigator(screen = NovelDetailScreen(sourceId, path)) { navigator ->
                                DefaultNavigatorScreenTransition(navigator)
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
        } finally {
            scenario?.close()
            novelId?.let { repository.remove(it) }
            repository.sources.fixtures.remove(sourceId)
            repository.pause(wasPaused)
            context.appGraph.basePreferences.incognitoMode.set(wasIncognito)
        }
    }
}
