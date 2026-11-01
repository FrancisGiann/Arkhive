package mihon.readingapp.library

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
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.novels.NovelCatalogPage
import mihon.readingapp.novels.NovelChapter
import mihon.readingapp.novels.NovelSource
import mihon.readingapp.novels.SourceNovel
import mihon.readingapp.settings.InkSettingsTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/** Uses only the existing HomeScreen/LibraryDashboard entry point and synthetic manga/novel rows. */
@RunWith(AndroidJUnit4::class)
class LibrarySelectionLayoutRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test
    fun mixedNovelAndComicSelectionActionsRemainReadableAtNarrowLargeText() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val uiVariant = InstrumentationRegistry.getArguments().getString("arkhive_ui_variant", "revised")
        assertTrue("Pass arkhive_ui_variant=baseline or revised", uiVariant in setOf("baseline", "revised"))
        val sourceId = "qa.library-selection.$suffix"
        val path = "selection-$suffix"
        val novelTitle = "Fixture novel $suffix"
        val comicTitle = "Fixture comic $suffix"
        val source = object : NovelSource {
            override val id = sourceId
            override val name = "Synthetic selection fixture"
            override val site = "https://fixture.invalid"
            override suspend fun catalog(page: Int, search: String, genre: String?) =
                NovelCatalogPage(emptyList(), null, "Synthetic library selection fixture")
            override suspend fun detail(path: String) = SourceNovel(
                path = path,
                name = novelTitle,
                author = "Fixture author",
                chapters = listOf(NovelChapter("chapter-1", "Chapter 1")),
                provenanceUrl = site,
                rights = "Synthetic fixture only",
            )
            override suspend fun chapter(path: String) = "Fixture chapter text"
        }
        val graph = context.appGraph
        val repository = graph.novelRepository
        val wasPaused = repository.state.value.paused
        val wasIncognito = graph.basePreferences.incognitoMode.get()
        var novelId: String? = null
        var mangaId: Long? = null
        var scenario: ActivityScenario<MainActivity>? = null
        repository.sources.fixtures[sourceId] = source
        try {
            repository.pause(true)
            graph.basePreferences.incognitoMode.set(false)
            val loadedNovelId = repository.load(sourceId, path).id
            novelId = loadedNovelId
            repository.favorite(loadedNovelId, true)
            val manga = graph.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = OriginalDemoSource.MANGA_ID,
                        url = "/qa-library-selection-$suffix",
                        title = comicTitle,
                        author = "Fixture artist",
                        initialized = true,
                    ),
                ),
            ).single()
            mangaId = manga.id
            assertTrue("Synthetic comic should be favorited for the library fixture", graph.mangaRepository.update(
                MangaUpdate(manga.id) { favoriteAt = Clock.System.now() },
            ))

            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        val density = LocalDensity.current
                        InkSettingsTheme {
                            CompositionLocalProvider(
                                LocalDensity provides Density(density.density, fontScale = 1.3f),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                                        Navigator(screen = HomeScreen) { navigator ->
                                            DefaultNavigatorScreenTransition(navigator)
                                        }
                                    }
                                }
                            }
                        }
                    }
                })
            }

            assertNotNull("Library entry point is ready", device.wait(Until.findObject(By.text("YOUR LIBRARY")), 15_000))
            requireNotNull(device.wait(Until.findObject(By.desc("Search library")), 5_000)).click()
            val searchField = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
            searchField.text = suffix
            device.pressBack()
            assertNotNull("Synthetic novel is visible through LibraryDashboard", device.wait(Until.findObject(By.text(novelTitle)), 8_000))
            assertNotNull("Synthetic comic shares the same search result", device.wait(Until.findObject(By.text(comicTitle)), 5_000))
            requireNotNull(device.findObject(By.text(novelTitle))).longClick()
            assertNotNull("Long press selects the novel", device.wait(Until.findObject(By.text("1 selected")), 5_000))
            assertTrue("Single selection retains Series options", device.hasObject(By.text("Series options")))
            assertNotNull("Selection header keeps an explicit close control", device.findObject(By.desc("Close selection")))

            requireNotNull(device.findObject(By.text(comicTitle))).click()
            assertNotNull("Background tap adds the comic to the active selection", device.wait(Until.findObject(By.text("2 selected")), 5_000))
            assertTrue("Series options is hidden for a mixed multi-selection", !device.hasObject(By.text("Series options")))
            assertTrue("Routine actions remain visible in the overlay", device.hasObject(By.text("Add to shelf")) && device.hasObject(By.text("Organize")))
            assertTrue("Capture the real mixed-selection overlay at 320dp/1.3x", device.takeScreenshot(File(context.cacheDir, "qa-library-selection-320dp-1.3x-$uiVariant.png")))

            repeat(5) {
                if (!device.hasObject(By.text("Delete copies"))) {
                    device.swipe(
                        device.displayWidth / 2,
                        (device.displayHeight * .84f).toInt(),
                        device.displayWidth / 2,
                        (device.displayHeight * .56f).toInt(),
                        18,
                    )
                }
            }
            assertTrue("Cleanup actions remain reachable by scrolling", device.hasObject(By.text("Remove from library")) && device.hasObject(By.text("Delete copies")))
            assertTrue("Capture the lower cleanup actions", device.takeScreenshot(File(context.cacheDir, "qa-library-selection-cleanup-320dp-1.3x-$uiVariant.png")))
            if (uiVariant == "revised") {
                assertTrue("The cleanup label remains legible", device.hasObject(By.text("Library cleanup")))
            }

            if (uiVariant == "revised") {
                device.pressBack()
                assertTrue("Back clears selection before leaving the library", !device.hasObject(By.text("2 selected")))
                assertTrue("Back keeps the user on the library screen", device.hasObject(By.text("YOUR LIBRARY")))
            } else {
                requireNotNull(device.findObject(By.desc("Close selection"))).click()
            }
        } finally {
            scenario?.close()
            mangaId?.let { id ->
                graph.mangaRepository.update(MangaUpdate(id) { favoriteAt = null })
                deleteFixtureManga(id)
            }
            novelId?.let { repository.remove(it) }
            repository.sources.fixtures.remove(sourceId)
            repository.pause(wasPaused)
            graph.basePreferences.incognitoMode.set(wasIncognito)
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
}
