package mihon.readingapp.library

import app.cash.sqldelight.db.SqlDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.UUID
import kotlin.time.Clock

/** Uses two authored Mihon-compatible sources to exercise selected native edition mapping. */
@RunWith(AndroidJUnit4::class)
class NativeAlternateMigrationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun selectedNativeEditionsCompareAndTransferOnlyCompletedStateAndBookmarks(): Unit = runBlocking {
        val graph = context.appGraph
        val nonce = UUID.randomUUID().toString()
        var driver: SqlDriver? = null
        var previousId: Long? = null
        var targetId: Long? = null
        try {
            val activeDriver = DatabaseBindings.providesSqlDriver(context, DatabaseRepositoryImpl(lazy { requireNotNull(driver) }))
            driver = activeDriver
            val database = DatabaseBindings.providesDatabase(activeDriver)
            val mangas = MangaRepositoryImpl(database)
            val chapters = ChapterRepositoryImpl(database)
            val previous = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = OriginalDemoSource.MANGA_ID,
                url = "/neon-courier/selected-edition-qa-$nonce",
                title = "Original selected manga edition A $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            previousId = previous.id
            val target = NetworkToLocalManga(mangas)(Manga.create().copy(
                source = OriginalDemoSource.WEBTOON_ID,
                url = "/rainlight-district/selected-edition-qa-$nonce",
                title = "Original selected manga edition B $nonce",
                initialized = true,
                favoriteAt = Clock.System.now(),
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
            ))
            targetId = target.id

            val previousKey = "manga:${previous.id}"
            val targetKey = "manga:${target.id}"
            val plan = graph.seriesToolsRepository.compareMangaEditions(previousKey, targetKey)
            assertEquals("The two authored source fixtures expose the same title and number mapping", 3, plan.verified.size)
            assertTrue(plan.ambiguous.isEmpty())
            assertEquals(0, plan.unmatched)

            val previousChapter = chapters.getChapterByMangaId(previous.id).single { it.chapterNumber == 1.0 }
            val targetChapter = chapters.getChapterByMangaId(target.id).single { it.chapterNumber == 1.0 }
            chapters.update(ChapterUpdate(previousChapter.id) {
                read = true
                bookmark = true
                lastPageRead = 17
            })
            chapters.update(ChapterUpdate(targetChapter.id) {
                read = false
                bookmark = false
                lastPageRead = 3
            })

            val transferred = graph.seriesToolsRepository.migrateMangaProgress(previousKey, targetKey, reviewed = emptySet())
            assertEquals("All unique title-and-number matches are verified automatically", 3, transferred)
            val migrated = chapters.getChapterByMangaId(target.id).single { it.chapterNumber == 1.0 }
            assertTrue("Completed state transfers after a selected edition comparison", migrated.read)
            assertTrue("Bookmarks transfer after a selected edition comparison", migrated.bookmark)
            assertEquals("Page offsets are edition-specific and stay at the target value", 3, migrated.lastPageRead)
            assertTrue("The source edition remains saved and readable", mangas.getMangaById(previous.id).favorite)
            assertTrue("The selected target remains in the library", mangas.getMangaById(target.id).favorite)
        } finally {
            driver?.let { activeDriver ->
                listOfNotNull(previousId, targetId).forEach { id ->
                    runCatching { graph.mangaRepository.update(MangaUpdate(id) { favoriteAt = null }) }
                }
                withContext(Dispatchers.IO) {
                    listOfNotNull(previousId, targetId).forEach { id ->
                        runCatching { activeDriver.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await() }
                    }
                }
                activeDriver.close()
            }
        }
    }
}
