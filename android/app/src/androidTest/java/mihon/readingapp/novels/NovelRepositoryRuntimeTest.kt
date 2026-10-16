package mihon.readingapp.novels

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import mihon.readingapp.library.SmartOfflineBudget
import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NovelRepositoryRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(preloadAllowed:()->Boolean={false},test:suspend (OriginalNovelFixture,NovelRepository,String)->Unit) {
        val f=OriginalNovelFixture();val name="novel-qa-${UUID.randomUUID()}.db";val sources=context.appGraph.novelRepository.sources
        sources.fixtures[f.id]=f.source
        val repo=NovelRepository(context,sources,name,false,preloadNetworkAllowed=preloadAllowed)
        try {test(f,repo,name)} finally {
            runCatching {repo.closeForTest()};sources.fixtures.remove(f.id);context.deleteDatabase(name)
            val folder="arkhive-novel-test-"+NovelRepository.id(name).take(16)
            File(context.filesDir,folder).deleteRecursively();File(context.cacheDir,folder).deleteRecursively()
        }
    }
    @Test fun nextChapterPreloadUsesOnlyTemporaryCacheAndRespectsNetworkPolicy():Unit=runBlocking {
        fixture(preloadAllowed={true}) {f,repo,_ ->
            val novel=repo.load(f.id,f.first);val chapters=repo.chapters(novel.id)
            repo.readerForeground(true)
            val calls=f.calls.get()
            assertTrue(repo.preloadNextText(novel.id,chapters.first().id))
            assertEquals(calls+1,f.calls.get())
            val next=repo.chapters(novel.id)[1]
            assertTrue(next.cached);assertFalse(next.downloaded)
            assertTrue(repo.state.value.downloads.isEmpty());assertEquals(0L,repo.savedBytes(novel.id))
            val cachedText=repo.text(novel.id,next.id)
            assertTrue(cachedText.contains("lantern keeper"));assertEquals(calls+1,f.calls.get())
            repo.readerForeground(false)
        }
        fixture(preloadAllowed={false}) {f,repo,_ ->
            val novel=repo.load(f.id,f.first);val chapters=repo.chapters(novel.id)
            repo.readerForeground(true)
            val calls=f.calls.get()
            assertFalse(repo.preloadNextText(novel.id,chapters.first().id))
            assertEquals(calls,f.calls.get())
            assertFalse(repo.chapters(novel.id)[1].cached)
            assertTrue(repo.state.value.downloads.isEmpty())
            repo.readerForeground(false)
        }
    }
    @Test fun titleSavedAggregateTracksDurableCopiesAndNeverCountsTemporaryCache():Unit=runBlocking {fixture {f,repo,_ ->
        val novel=repo.load(f.id,f.first);val chapters=repo.chapters(novel.id)
        assertEquals(0,requireNotNull(repo.get(novel.id)).savedChapters)
        assertTrue(repo.text(novel.id,chapters[2].id).contains("lantern keeper"))
        assertEquals("temporary cache is not a saved chapter",0,requireNotNull(repo.get(novel.id)).savedChapters)
        assertEquals(2,repo.queue(novel.id,chapters.take(2).map {it.id}))
        val first=requireNotNull(repo.claim());repo.runDownload(first)
        val partial=requireNotNull(repo.get(novel.id))
        assertEquals(6,partial.totalChapters);assertEquals(1,partial.savedChapters)
        assertEquals(1,repo.state.value.downloads.count {it.novelId==novel.id && it.state=="queued"})
        repo.removeSaved(chapters.first().id)
        assertEquals(0,requireNotNull(repo.get(novel.id)).savedChapters)
        assertEquals(.0,repo.chapters(novel.id).first().progression,.0001)
    }}
    @Test fun preloadCancellationNeverBlocksForegroundAndRetainsSingleSlotUntilSourceExits():Unit=runBlocking {
        fixture(preloadAllowed={true}) {f,repo,_ ->
            val novel=repo.load(f.id,f.first);val chapters=repo.chapters(novel.id)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val calls=java.util.concurrent.atomic.AtomicInteger()
            repo.sources.fixtures[f.id]=object:NovelSource by f.source {
                override suspend fun chapter(path:String):String {
                    calls.incrementAndGet()
                    if(path==chapters[1].path) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { withTimeout(10_000) { release.await() } }
                    }
                    return f.text()
                }
            }
            repo.readerForeground(true)
            val preload=async(Dispatchers.IO) { repo.preloadNextText(novel.id,chapters.first().id) }
            withTimeout(5_000) { entered.await() }
            // Foreground text cancels speculation but must finish while the simulated source is
            // still draining its own bounded operation.
            assertTrue(withTimeout(3_000) { repo.text(novel.id,chapters.first().id) }.contains("lantern keeper"))
            assertFalse(repo.preloadNextText(novel.id,chapters.first().id))
            assertEquals(2,calls.get())
            assertFalse(repo.chapters(novel.id)[1].cached)
            assertTrue(repo.state.value.downloads.isEmpty())
            release.complete(Unit)
            withTimeout(5_000) { preload.join() }
            assertFalse(repo.chapters(novel.id)[1].cached)
            repo.readerForeground(false)
        }
    }
    @Test fun metadataRefreshPreservesProgressBookmarksAndOfflineCache():Unit=runBlocking {fixture {f,repo,_->
        val novel=repo.load(f.id,f.first);val chapters=repo.chapters(novel.id)
        assertEquals(6,chapters.size);repo.favorite(novel.id,true);repo.bookmark(chapters[0].id,true)
        repo.saveProgress(novel.id,chapters[0].id,.42).join();val body=repo.text(novel.id,chapters[0].id)
        repo.load(f.id,f.first)
        val same=repo.chapters(novel.id)[0];assertTrue(same.bookmark);assertEquals(.42,same.progression,.0001)
        assertEquals(6,repo.get(novel.id)!!.totalChapters);assertEquals(.07,repo.get(novel.id)!!.progression,.0001)
        f.offline.set(true);val requests=f.calls.get();assertEquals(body,repo.text(novel.id,same.id));assertEquals(requests,f.calls.get())
        repo.removeSaved(same.id);assertFalse(repo.chapters(novel.id)[0].cached)
        assertTrue(repo.chapters(novel.id)[0].bookmark);assertEquals(.42,repo.chapters(novel.id)[0].progression,.0001)
    }}
    @Test fun coldRepositoryConstructionPublishesPersistedFavoritesAfterInitialization():Unit=runBlocking {fixture {f,repo,name->
        val original=repo.load(f.id,f.first)
        repo.favorite(original.id,true)
        repo.saveProgress(original.id,repo.chapters(original.id).first().id,.28).join()
        repo.closeForTest()

        // Reopening forces constructor startup to publish a real favorite through the same
        // query used by the cold StateFlow. The store and source are isolated to this fixture.
        val cold=NovelRepository(context,repo.sources,name,false)
        try {
            val restored=withTimeout(5_000) { cold.get(original.id) }
            assertNotNull(restored)
            assertTrue(requireNotNull(restored).favorite)
            assertEquals(6,restored.totalChapters)
            assertEquals(original.id,cold.state.value.novels.single().id)
            assertEquals(.28,restored.chapterProgress,.0001)
            assertEquals(.28/6.0,restored.progression,.0001)
        } finally { cold.closeForTest() }
    }}
    @Test fun savedRedirectStubIsRefetchedAndRepairedWithoutChangingReadingState():Unit=runBlocking {fixture {f,repo,name->
        val novel=repo.load(f.id,f.first);val chapter=repo.chapters(novel.id).first()
        repo.bookmark(chapter.id,true);repo.saveProgress(novel.id,chapter.id,.42).join()
        repo.queue(novel.id,listOf(chapter.id));repo.runDownload(repo.claim()!!)
        assertTrue(repo.chapters(novel.id).first().downloaded)
        val folder="arkhive-novel-test-"+NovelRepository.id(name).take(16)
        val saved=File(File(context.filesDir,folder),chapter.id+".html")
        saved.writeText("<div><div><p>Redirect to:</p><ul><li>The War of the Worlds (1898)/Book 1/Chapter 1</li></ul></div></div>")
        val calls=f.calls.get()
        val body=repo.text(novel.id,chapter.id)
        assertTrue(body.contains("lantern keeper"));assertEquals(calls+1,f.calls.get())
        assertFalse(NovelHtmlPolicy.isRedirectStub(saved.readText()))
        val unchanged=repo.chapters(novel.id).first()
        assertTrue(unchanged.downloaded);assertTrue(unchanged.bookmark);assertEquals(.42,unchanged.progression,.0001)
    }}
    @Test fun smartNovelSaveUsesTheGuardedOfflineEngineAndHonorsWifiBudgetAndReaderState():Unit=runBlocking {fixture {f,repo,_ ->
        val novel=repo.load(f.id,f.first);repo.favorite(novel.id,true)
        val chapters=repo.chapters(novel.id)
        repo.bookmark(chapters[0].id,true);repo.saveProgress(novel.id,chapters[0].id,.31).join()

        var wifi=true
        val savedBytes=repo.downloadSmartChapter(novel.id,chapters[0].id,
            SmartOfflineBudget({1024*1024L},0,{wifi},{true}))
        assertTrue(savedBytes>0)
        assertTrue(repo.chapters(novel.id).first().downloaded)
        assertTrue(repo.chapters(novel.id).first().bookmark)
        assertEquals(.31,repo.chapters(novel.id).first().progression,.0001)
        assertTrue(repo.state.value.downloads.isEmpty())

        val requestCount=f.calls.get()
        wifi=false
        assertThrows(SmartOfflineBlockedException::class.java) {
            runBlocking {repo.downloadSmartChapter(novel.id,chapters[1].id,SmartOfflineBudget({1024*1024L},0,{wifi},{true}))}
        }
        assertEquals("Wi-Fi policy blocks before the network request",requestCount,f.calls.get())

        wifi=true
        assertThrows(SmartOfflineBlockedException::class.java) {
            runBlocking {repo.downloadSmartChapter(novel.id,chapters[2].id,SmartOfflineBudget({0L},0,{wifi},{true}))}
        }
        repo.readerForeground(true)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {repo.downloadSmartChapter(novel.id,chapters[3].id,SmartOfflineBudget({1024*1024L},0,{wifi},{true}))}
        }
        repo.readerForeground(false)
        assertFalse(repo.chapters(novel.id).drop(1).any {it.downloaded})
    }}
    @Test fun multiTitleBatchPauseRestartRetryAndOfflineReadingAreDurable():Unit=runBlocking {fixture {f,repo,name->
        val a=repo.load(f.id,f.first);val b=repo.load(f.id,f.second)
        val ca=repo.chapters(a.id);val cb=repo.chapters(b.id)
        repo.pause(true);assertEquals(6,repo.queue(a.id,ca.map {it.id}));assertEquals(6,repo.queue(b.id,cb.map {it.id}));assertEquals(0,repo.queue(a.id,ca.map {it.id}))
        assertNull(repo.claim());repo.closeForTest()
        val restored=NovelRepository(context,repo.sources,name,false)
        try {
            restored.flush();assertTrue(restored.state.value.paused);assertEquals(12,restored.state.value.downloads.size)
            restored.pause(false);f.failOnce.set(true);val first=restored.claim()!!;restored.runDownload(first)
            assertEquals("retry",restored.state.value.downloads.first {it.id==first.id}.state)
            repeat(20) {restored.claim()?.let {restored.runDownload(it)}}
            assertTrue(restored.state.value.downloads.isEmpty());assertEquals(6,restored.chapters(a.id).count {it.downloaded});assertEquals(6,restored.chapters(b.id).count {it.downloaded})
            f.offline.set(true);val calls=f.calls.get();assertTrue(restored.text(b.id,cb.last().id).contains("lantern keeper"));assertEquals(calls,f.calls.get())
            restored.removeSaved(ca[0].id);assertFalse(restored.chapters(a.id)[0].downloaded)
        } finally {restored.closeForTest()}
    }}
    @Test fun stalePausedAttemptCannotOverwriteNewClaimOrCommitSavedText():Unit=runBlocking {fixture {f,repo,_->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        repo.sources.fixtures[f.id]=object:NovelSource by f.source {
            override suspend fun chapter(path:String):String {entered.complete(Unit);withContext(NonCancellable){withTimeout(10000){release.await()}};return f.text()}
        }
        val novel=repo.load(f.id,f.first);val c=repo.chapters(novel.id)[0];repo.queue(novel.id,listOf(c.id));val first=repo.claim()!!
        val old=launch {repo.runDownload(first)};withTimeout(5000){entered.await()}
        repo.pause(true);repo.pause(false);val newer=repo.claim()!!;assertNotEquals(first.token,newer.token)
        release.complete(Unit);old.join()
        val queued=repo.state.value.downloads.single();assertEquals(newer.token,queued.token);assertEquals("downloading",queued.state);assertFalse(repo.chapters(novel.id)[0].downloaded)
        repo.sources.fixtures[f.id]=f.source;repo.runDownload(newer);assertTrue(repo.chapters(novel.id)[0].downloaded)
    }}
    @Test fun cancelDuringSourceResponseCannotResurrectQueueOrSavedChapter():Unit=runBlocking {fixture {f,repo,_->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        repo.sources.fixtures[f.id]=object:NovelSource by f.source {override suspend fun chapter(path:String):String {entered.complete(Unit);withContext(NonCancellable){withTimeout(10000){release.await()}};return f.text()}}
        val novel=repo.load(f.id,f.first);val c=repo.chapters(novel.id)[0];repo.queue(novel.id,listOf(c.id));val job=repo.claim()!!
        val download=launch {repo.runDownload(job)};withTimeout(5000){entered.await()};repo.cancel(c.id);release.complete(Unit);download.join()
        assertTrue(repo.state.value.downloads.isEmpty());assertFalse(repo.chapters(novel.id)[0].downloaded)
        repo.readerForeground(true);repo.queue(novel.id,listOf(c.id));assertNull(repo.claim());repo.readerForeground(false);assertNotNull(repo.claim())
    }}
    @Test fun atomicFileCommitBeforeDatabaseAckIsRecoveredWithoutFetchingAgain():Unit=runBlocking {fixture {f,repo,name->
        val novel=repo.load(f.id,f.first);val c=repo.chapters(novel.id)[0];repo.bookmark(c.id,true);repo.saveProgress(novel.id,c.id,.35).join()
        repo.queue(novel.id,listOf(c.id));assertNotNull(repo.claim())
        val dir=File(context.filesDir,"arkhive-novel-test-"+NovelRepository.id(name).take(16));File(dir,c.id+".html").writeText(f.text());File(dir,"chapter-interrupted.part").writeText("incomplete")
        repo.closeForTest();f.offline.set(true)
        val restored=NovelRepository(context,repo.sources,name,false)
        try {
            restored.flush();assertTrue(restored.state.value.downloads.isEmpty());assertFalse(File(dir,"chapter-interrupted.part").exists())
            val saved=restored.chapters(novel.id)[0];assertTrue(saved.downloaded);assertTrue(saved.bookmark);assertEquals(.35,saved.progression,.0001)
            val calls=f.calls.get();assertTrue(restored.text(novel.id,c.id).contains("lantern keeper"));assertEquals(calls,f.calls.get())
        } finally {restored.closeForTest()}
    }}
}
