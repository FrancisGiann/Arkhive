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
import app.cash.sqldelight.db.SqlDriver
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.settings.InkSettingsTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.util.UUID
import kotlin.time.Clock

@RunWith(AndroidJUnit4::class)
class MangaDetailLayoutRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test
    fun sharedComicHeroFitsNarrowLargeTextAndKeepsFullTitleAndActionsAccessible() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val uiVariant = InstrumentationRegistry.getArguments().getString("arkhive_ui_variant", "revised")
        assertTrue("Pass arkhive_ui_variant=baseline or revised", uiVariant in setOf("baseline", "revised"))
        val title = "A Fixture Comic With a Long, Searchable Title Across Many Kingdoms $suffix"
        val manga = context.appGraph.mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = OriginalDemoSource.MANGA_ID,
                    url = "/qa-detail-layout-$suffix",
                    title = title,
                    author = "Fixture artist",
                    initialized = true,
                    chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
                ),
            ),
        ).single()
        var fixtureChapters: List<Chapter> = emptyList()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val graph = context.appGraph
            graph.mangaRepository.update(MangaUpdate(manga.id) { favoriteAt = Clock.System.now() })
            fixtureChapters = graph.chapterRepository.updateFromRemote(
                emptyList(),
                (1..250).map { number ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "${manga.url}/chapter/$number",
                        name = "Chapter $number",
                        chapterNumber = number.toDouble(),
                        sourceOrder = (number - 1).toLong(),
                        dateFetch = Clock.System.now(),
                    )
                },
                emptyList(),
            )
            for (fontScale in listOf(1.3f, 2f)) {
                val launched = ActivityScenario.launch(MainActivity::class.java)
                scenario = launched
                launched.onActivity { activity ->
                    activity.setContentView(ComposeView(activity).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        setContent {
                            val density = LocalDensity.current
                            InkSettingsTheme {
                                CompositionLocalProvider(
                                    LocalDensity provides Density(density.density, fontScale = fontScale),
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                                            Navigator(screen = MangaScreen(manga.id)) { navigator ->
                                                DefaultNavigatorScreenTransition(navigator)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                }

                val scaleLabel = if (fontScale == 1.3f) "1.3x" else "2.0x"
                val titleNode = device.wait(Until.findObject(By.desc(title)), 15_000)
                assertNotNull("The complete comic title remains accessible at 320dp/$scaleLabel", titleNode)
                assertTrue("Comic library state remains visible", device.hasObject(By.textContains("In library")))
                assertTrue("Comic More actions remain visible", device.hasObject(By.text("More")))
                assertTrue("Long title has rendered bounds", requireNotNull(titleNode).visibleBounds.height() > 0)
                assertTrue("Capture the responsive comic hero", device.takeScreenshot(File(context.cacheDir, "qa-manga-detail-hero-320dp-$scaleLabel-$uiVariant.png")))

                val pageOne = pageDescription(uiVariant, 1, 3, "1 to 100 of 250")
                for (attempt in 0 until 18) {
                    if (device.hasObject(By.desc(pageOne))) break
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * .78f).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * .57f).toInt(),
                        20,
                    )
                }
                val pagerNode = requireNotNull(device.wait(Until.findObject(By.desc(pageOne)), 5_000)) {
                    "MangaScreen should expose the shared page control after scrolling"
                }
                val densityScale = context.resources.displayMetrics.density
                assertTrue("Comic page target remains at least 48dp", pagerNode.visibleBounds.height() >= (48 * densityScale).toInt())
                assertTrue("Comic pager is within the 320dp viewport", pagerNode.visibleBounds.left >= (device.displayWidth - (320 * densityScale).toInt()) / 2)
                assertTrue("Comic pager does not overflow the 320dp viewport", pagerNode.visibleBounds.right <= (device.displayWidth + (320 * densityScale).toInt()) / 2)
                assertTrue("Capture the shared comic pager at 320dp/$scaleLabel", device.takeScreenshot(File(context.cacheDir, "qa-manga-detail-pager-320dp-$scaleLabel-$uiVariant.png")))

                if (uiVariant == "revised" && device.hasObject(By.desc("More chapter actions"))) {
                    requireNotNull(device.findObject(By.desc("More chapter actions"))).click()
                    assertNotNull("Manga retains Next unread in narrow action overflow", device.wait(Until.findObject(By.text("Next unread")), 5_000))
                    device.pressBack()
                } else {
                    assertTrue("Manga keeps Next unread reachable", device.hasObject(By.text("Next unread")))
                }
                requireNotNull(device.findObject(By.desc("Next page"))).click()
                assertNotNull("Manga page advances to its next bounded slice", device.wait(Until.findObject(By.desc(pageDescription(uiVariant, 2, 3, "101 to 200 of 250"))), 5_000))
                assertTrue("The next manga chapter slice is visible", device.hasObject(By.text("Chapter 101")))
                launched.close()
                scenario = null
            }
        } finally {
            scenario?.close()
            if (fixtureChapters.isNotEmpty()) {
                context.appGraph.chapterRepository.updateFromRemote(fixtureChapters.map(Chapter::id), emptyList(), emptyList())
            }
            context.appGraph.mangaRepository.update(MangaUpdate(manga.id) { favoriteAt = null })
            deleteFixtureManga(manga.id)
        }
    }

    private suspend fun deleteFixtureManga(id: Long) {
        lateinit var driver: SqlDriver
        val migration = DatabaseRepositoryImpl(lazy { driver })
        driver = DatabaseBindings.providesSqlDriver(context, migration)
        try {
            DatabaseBindings.providesDatabase(driver)
            driver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await()
        } finally {
            driver.close()
        }
    }

    private fun pageDescription(variant: String, page: Int, pages: Int, chapterRange: String): String =
        if (variant == "revised") {
            "Page $page of $pages. Chapters $chapterRange. Find or jump to a chapter or search."
        } else {
            "Page $page of $pages. Find or jump to a chapter or search."
        }
}
