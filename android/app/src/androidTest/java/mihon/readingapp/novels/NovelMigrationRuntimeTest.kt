package mihon.readingapp.novels

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NovelMigrationRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun withRepository(
        sources: List<ControlledNovelSource>,
        test: suspend (NovelRepository) -> Unit,
    ) {
        val name = "novel-migration-${UUID.randomUUID()}.db"
        val registry = context.appGraph.novelRepository.sources
        sources.forEach { registry.fixtures[it.id] = it }
        val repo = NovelRepository(context, registry, name, false)
        try {
            test(repo)
        } finally {
            runCatching { repo.closeForTest() }
            sources.forEach { registry.fixtures.remove(it.id) }
            context.deleteDatabase(name)
            val folder = "arkhive-novel-test-" + NovelRepository.id(name).take(16)
            File(context.filesDir, folder).deleteRecursively()
            File(context.cacheDir, folder).deleteRecursively()
        }
    }

    @Test fun migrationTransfersCompletedAndBookmarkedStateButKeepsPartialOffsetsLocal() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val previousSource = ControlledNovelSource("qa.migrate.old.$nonce", "Old edition", "Original Lantern Atlas", listOf(
            NovelChapter("old-1", "Chapter One", 1.0),
            NovelChapter("old-2", "Chapter Two", 2.0),
            NovelChapter("old-3", "Chapter Three", 3.0),
            NovelChapter("old-4", "Current Chapter", 4.0),
        ))
        val targetSource = ControlledNovelSource("qa.migrate.new.$nonce", "New edition", "Original Lantern Atlas", listOf(
            NovelChapter("new-1", "Chapter One", 1.0),
            NovelChapter("new-2", "Chapter Two", 2.0),
            NovelChapter("new-3", "Retitled Three", 3.0),
            NovelChapter("new-4", "Retitled Current", 4.0),
            NovelChapter("new-5", "Unmatched Chapter", 5.0),
        ))

        withRepository(listOf(previousSource, targetSource)) { repo ->
            val previous = repo.load(previousSource.id, previousSource.workPath)
            val target = repo.load(targetSource.id, targetSource.workPath)
            val oldByPath = repo.chapters(previous.id).associateBy { it.path }
            val newByPath = repo.chapters(target.id).associateBy { it.path }

            repo.saveProgress(previous.id, oldByPath.getValue("old-1").id, 0.42).join()
            repo.bookmark(oldByPath.getValue("old-1").id, true)
            repo.saveProgress(previous.id, oldByPath.getValue("old-2").id, 0.999).join()
            repo.bookmark(oldByPath.getValue("old-3").id, true)
            repo.saveProgress(previous.id, oldByPath.getValue("old-4").id, 0.27).join()

            repo.saveProgress(target.id, newByPath.getValue("new-1").id, 0.11).join()
            repo.saveProgress(target.id, newByPath.getValue("new-2").id, 0.15).join()
            repo.saveProgress(target.id, newByPath.getValue("new-3").id, 0.25).join()
            repo.saveProgress(target.id, newByPath.getValue("new-4").id, 0.35).join()
            repo.saveProgress(target.id, newByPath.getValue("new-5").id, 0.60).join()

            val plan = repo.previewMigration(previous.id, target.id)
            assertEquals(2, plan.verified.size)
            assertEquals(2, plan.ambiguous.size)
            assertEquals(1, plan.unmatched)
            val reviewed = plan.ambiguous.map { it.key }.toSet()
            assertEquals(4, repo.migrateProgress(previous.id, target.id, reviewed))

            val migrated = repo.chapters(target.id).associateBy { it.path }
            assertEquals("A partial position from the old text must not overwrite the target edition.",
                0.11, migrated.getValue("new-1").progression, 0.0001)
            assertTrue(migrated.getValue("new-1").bookmark)
            assertEquals(0.999, migrated.getValue("new-2").progression, 0.0001)
            assertTrue(migrated.getValue("new-3").bookmark)
            assertEquals(0.25, migrated.getValue("new-3").progression, 0.0001)
            assertEquals(0.35, migrated.getValue("new-4").progression, 0.0001)
            assertEquals(0.60, migrated.getValue("new-5").progression, 0.0001)
            assertEquals("The mapped current chapter should follow the original entry without a fractional offset.",
                migrated.getValue("new-4").id, repo.get(target.id)!!.lastChapter)
            assertEquals(0.35, repo.get(target.id)!!.chapterProgress, 0.0001)
            assertEquals(0.27, oldByPath.getValue("old-4").let { repo.chapters(previous.id).first { c -> c.id == it.id }.progression }, 0.0001)
        }
    }

    @Test fun conflictingReviewedCandidatesAreRejectedWithoutChangingTargetProgress() = runBlocking {
        val nonce = UUID.randomUUID().toString()
        val previousSource = ControlledNovelSource("qa.migrate.conflict.old.$nonce", "Old edition", "Original Circuit", listOf(
            NovelChapter("old-a", "Part A", 1.0),
            NovelChapter("old-b", "Part B", 1.0),
        ))
        val targetSource = ControlledNovelSource("qa.migrate.conflict.new.$nonce", "New edition", "Original Circuit", listOf(
            NovelChapter("new-a", "Retitled Part", 1.0),
        ))

        withRepository(listOf(previousSource, targetSource)) { repo ->
            val previous = repo.load(previousSource.id, previousSource.workPath)
            val target = repo.load(targetSource.id, targetSource.workPath)
            val targetChapter = repo.chapters(target.id).single()
            repo.saveProgress(target.id, targetChapter.id, 0.37).join()
            val plan = repo.previewMigration(previous.id, target.id)
            assertEquals(2, plan.ambiguous.size)
            val before = repo.chapters(target.id).single()

            val failure = runCatching {
                repo.migrateProgress(previous.id, target.id, plan.ambiguous.map { it.key }.toSet())
            }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("only one chapter"))
            val after = repo.chapters(target.id).single()
            assertEquals(before.progression, after.progression, 0.0001)
            assertEquals(before.bookmark, after.bookmark)
        }
    }
}
