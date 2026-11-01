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
                ),
            ),
        ).single()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            context.appGraph.mangaRepository.update(MangaUpdate(manga.id) { favoriteAt = Clock.System.now() })
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

            val titleNode = device.wait(Until.findObject(By.desc(title)), 15_000)
            assertNotNull("The complete comic title remains available to accessibility at 320dp/1.3x", titleNode)
            assertTrue("Comic library state remains visible", device.hasObject(By.textContains("In library")))
            assertTrue("Comic More actions remain visible", device.hasObject(By.text("More")))
            assertTrue("Long title has rendered bounds", requireNotNull(titleNode).visibleBounds.height() > 0)
            assertTrue("Capture the real responsive comic hero", device.takeScreenshot(File(context.cacheDir, "qa-manga-detail-hero-320dp-1.3x-$uiVariant.png")))
        } finally {
            scenario?.close()
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
}
