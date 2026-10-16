package mihon.readingapp.novels

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NovelUpgradeAndInboxRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun withRepository(source: ControlledNovelSource, test: suspend (NovelRepository, String) -> Unit) {
        val name = "novel-inbox-${UUID.randomUUID()}.db"
        val registry = context.appGraph.novelRepository.sources
        registry.fixtures[source.id] = source
        val repo = NovelRepository(context, registry, name, false)
        try {
            test(repo, name)
        } finally {
            runCatching { repo.closeForTest() }
            registry.fixtures.remove(source.id)
            context.deleteDatabase(name)
            val folder = "arkhive-novel-test-" + NovelRepository.id(name).take(16)
            File(context.filesDir, folder).deleteRecursively()
            File(context.cacheDir, folder).deleteRecursively()
        }
    }

    @Test fun schemaV1UpgradeKeepsProgressAndBookmarksAndDatesOnlyNewlyObservedChapters() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val source = ControlledNovelSource("qa.upgrade.$nonce", "Original Atlas", "Original Atlas", listOf(
            NovelChapter("existing-chapter", "Existing Chapter", 1.0),
        ))

        val databaseName = "novel-v1-${UUID.randomUUID()}.db"
        val registry = context.appGraph.novelRepository.sources
        registry.fixtures[source.id] = source
        val novelId = NovelRepository.novelId(source.id, source.workPath)
        val chapterId = NovelRepository.chapterId(novelId, "existing-chapter")
        try {
            val detail = source.detail(source.workPath).copy(chapters = emptyList())
            val encodedDetail = Json.encodeToString(SourceNovel.serializer(), detail)
            val oldDb = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            try {
                oldDb.execSQL("CREATE TABLE novels(id TEXT PRIMARY KEY,source TEXT NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,details TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,last_chapter TEXT,last_read INTEGER NOT NULL DEFAULT 0)")
                oldDb.execSQL("CREATE TABLE novel_chapters(id TEXT PRIMARY KEY,novel_id TEXT NOT NULL REFERENCES novels(id) ON DELETE CASCADE,path TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,progress REAL NOT NULL DEFAULT 0,bookmark INTEGER NOT NULL DEFAULT 0,saved INTEGER NOT NULL DEFAULT 0)")
                oldDb.execSQL("CREATE TABLE novel_queue(id TEXT PRIMARY KEY REFERENCES novel_chapters(id) ON DELETE CASCADE,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,error TEXT,token TEXT NOT NULL,added INTEGER NOT NULL)")
                oldDb.execSQL("CREATE TABLE novel_options(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                oldDb.execSQL("INSERT INTO novels(id,source,path,name,details,favorite,last_chapter,last_read) VALUES(?,?,?,?,?,1,?,?)",
                    arrayOf<Any>(novelId, source.id, source.workPath, source.workName, encodedDetail, chapterId, 123456789L))
                oldDb.execSQL("INSERT INTO novel_chapters(id,novel_id,path,name,position,active,progress,bookmark,saved) VALUES(?,?,?,?,0,1,0.48,1,0)",
                    arrayOf<Any>(chapterId, novelId, "existing-chapter", "Existing Chapter"))
                oldDb.version = 1
            } finally {
                oldDb.close()
            }

            val repo = NovelRepository(context, registry, databaseName, false)
            try {
                val upgradedBeforeRefresh = repo.chapters(novelId).single()
                assertEquals(0.48, upgradedBeforeRefresh.progression, 0.0001)
                assertTrue(upgradedBeforeRefresh.bookmark)
                assertEquals("v1 rows receive the neutral arrival default during schema upgrade.", 0L, upgradedBeforeRefresh.arrivedAt)
                assertEquals(chapterId, repo.get(novelId)!!.lastChapter)

                val beforeRefresh = System.currentTimeMillis()
                source.currentChapters = listOf(
                    NovelChapter("existing-chapter", "Existing Chapter Revised", 1.0),
                    NovelChapter("newly-observed", "Newly Observed Chapter", 2.0),
                )
                repo.load(source.id, source.workPath)

                val refreshed = repo.chapters(novelId).associateBy { it.path }
                assertEquals(0.48, refreshed.getValue("existing-chapter").progression, 0.0001)
                assertTrue(refreshed.getValue("existing-chapter").bookmark)
                assertEquals("Refreshing the retained v1 chapter must not rewrite its neutral arrival.",
                    0L, refreshed.getValue("existing-chapter").arrivedAt)
                assertTrue(refreshed.getValue("newly-observed").arrivedAt >= beforeRefresh)
                assertEquals(setOf("newly-observed"), repo.updates(beforeRefresh - 1).map { it.chapter.path }.toSet())
            } finally {
                runCatching { repo.closeForTest() }
            }
        } finally {
            registry.fixtures.remove(source.id)
            context.deleteDatabase(databaseName)
            val folder = "arkhive-novel-test-" + NovelRepository.id(databaseName).take(16)
            File(context.filesDir, folder).deleteRecursively()
            File(context.cacheDir, folder).deleteRecursively()
        }
    }

    @Test fun novelInboxIncludesOnlyNewUnreadFavoriteChaptersAndAdvancesAfterRead() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val source = ControlledNovelSource("qa.inbox.$nonce", "Original Circuit", "Original Circuit", listOf(
            NovelChapter("old-unread", "Previously Listed Chapter", 1.0),
        ))
        withRepository(source) { repo, _ ->
            val novel = repo.load(source.id, source.workPath)
            repo.favorite(novel.id, true)
            val old = repo.chapters(novel.id).single()
            repo.saveProgress(novel.id, old.id, 1.0).join()

            val since = System.currentTimeMillis()
            source.currentChapters = listOf(
                NovelChapter("old-unread", "Previously Listed Chapter", 1.0),
                NovelChapter("new-unread", "New Unread Chapter", 2.0),
                NovelChapter("newer-unread", "Newer Unread Chapter", 3.0),
            )
            repo.load(source.id, source.workPath)

            val rows = repo.updates(since - 1)
            assertEquals(setOf("new-unread", "newer-unread"), rows.map { it.chapter.path }.toSet())
            assertTrue(rows.all { it.chapter.arrivedAt >= since && it.chapter.progression < 0.999 })
            rows.first { it.chapter.path == "new-unread" }.let { repo.saveProgress(novel.id, it.chapter.id, 1.0).join() }
            assertEquals(setOf("newer-unread"), repo.updates(since - 1).map { it.chapter.path }.toSet())
            assertTrue(repo.updates(rows.maxOf { it.chapter.arrivedAt }).isEmpty())
        }
    }
}
