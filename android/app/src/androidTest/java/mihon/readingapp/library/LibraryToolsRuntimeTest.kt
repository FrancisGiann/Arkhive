package mihon.readingapp.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import mihon.readingapp.fixtures.OriginalDemoSource
import java.util.UUID
import kotlin.time.Clock

@RunWith(AndroidJUnit4::class)
class LibraryToolsRuntimeTest {
    private val graph get()=InstrumentationRegistry.getInstrumentation().targetContext.appGraph
    @Test fun hiddenMangaLeavesDefaultLibraryButRemainsInRawBackupMembership():Unit=runBlocking {
        val original=graph.libraryVisibility.hiddenSeries.get()
        val selected=graph.mangaRepository.insertNetworkManga(listOf(Manga.create().copy(
            source=OriginalDemoSource.MANGA_ID,url="/original-privacy-qa-${UUID.randomUUID()}",title="Original privacy fixture",initialized=true,
        ))).single()
        val key="manga:${selected.id}"
        val initiallyHidden=key in original
        try {
            // SQLite may reuse an ID from a previous fixture run. Remove only this fixture's
            // exact key for the baseline assertion; restore the complete original set below.
            graph.libraryVisibility.setHidden(setOf(key),false)
            val favoriteUpdated=graph.mangaRepository.update(MangaUpdate(selected.id) {favoriteAt=Clock.System.now()})
            val rawFavorite=graph.mangaRepository.getFavorites().any {it.id==selected.id}
            assertTrue("Fixture favorite update failed: id=${selected.id}, initiallyHidden=$initiallyHidden, rawFavorite=$rawFavorite",favoriteUpdated)
            assertTrue("Fixture was not present in raw favorites: id=${selected.id}, initiallyHidden=$initiallyHidden",rawFavorite)
            val initialVisible=graph.getLibraryManga.await().map {it.id}
            assertTrue("Fixture should appear before this test hides it: id=${selected.id}, initiallyHidden=$initiallyHidden, rawFavorite=$rawFavorite, visibleIds=$initialVisible",selected.id in initialVisible)
            graph.libraryVisibility.setHidden(setOf(key),true)
            assertFalse("Explicitly hidden fixture must leave the default library: id=${selected.id}",graph.getLibraryManga.await().any {it.id==selected.id})
            assertTrue("Backup membership must retain hidden favorites",graph.mangaRepository.getFavorites().any {it.id==selected.id})
            graph.libraryVisibility.setHidden(setOf(key),false)
            assertTrue("Unhidden fixture should return to the default library",graph.getLibraryManga.await().any {it.id==selected.id})
        } finally {graph.libraryVisibility.hiddenSeries.set(original);graph.mangaRepository.update(MangaUpdate(selected.id) {favoriteAt=null})}
    }
    @Test fun shelvesAndProfilesSurviveStoreReloadAndDoNotChangeReaderDefaults():Unit=runBlocking {
        val store=graph.libraryToolsStore;val original=store.state.value
        val defaultMode=graph.readerPreferences.defaultReadingMode.get()
        val key="book:"+"a".repeat(64)
        try {
            store.addShelf("Original fixture shelf")
            store.assign(setOf(key),ShelfAssignment(ShelfStatus.ON_HOLD,setOf("Original fixture shelf")))
            val mangaKey = "manga:123"
            val novelKey = "novel:" + "c".repeat(64)
            store.addToShelves(setOf(key, mangaKey, novelKey), setOf("Original fixture shelf", "Follow-up", "Follow-up"))
            store.setProfile(key,ReadingProfile("scroll",42,112,ReadingRoom.WARM_PAPER))
            store.setDashboardSelection("PDFs","Original fixture shelf")
            val reloaded=LibraryToolsStore(InstrumentationRegistry.getInstrumentation().targetContext)
            assertEquals(ShelfStatus.ON_HOLD,reloaded.state.value.assignments[key]!!.status)
            assertEquals(setOf("Original fixture shelf", "Follow-up"), reloaded.state.value.assignments[key]!!.shelves)
            assertEquals(setOf("Original fixture shelf", "Follow-up"), reloaded.state.value.assignments[mangaKey]!!.shelves)
            assertEquals(setOf("Original fixture shelf", "Follow-up"), reloaded.state.value.assignments[novelKey]!!.shelves)
            assertEquals(1, reloaded.state.value.shelves.count { it == "Follow-up" })
            assertEquals(112,reloaded.profile(key).autoSpeed)
            assertEquals(ReadingRoom.WARM_PAPER,reloaded.profile(key).room)
            assertEquals("PDFs",reloaded.state.value.dashboardFilter)
            assertEquals("Original fixture shelf",reloaded.state.value.dashboardCollection)
            assertEquals(defaultMode,graph.readerPreferences.defaultReadingMode.get())
            store.removeShelf("Original fixture shelf");assertEquals(setOf("Follow-up"),store.state.value.assignments[key]!!.shelves)
            assertEquals("Removing the active custom shelf clears only that stale filter","",store.state.value.dashboardCollection)
        } finally {store.update {original}}
    }
    @Test fun optionalStatsUseOnlyForegroundMonotonicIntervalsAndUniqueCompletions():Unit=runBlocking {
        val store=graph.libraryToolsStore;val original=store.state.value;val key="book:"+"b".repeat(64)
        var elapsed=1000L;var eligible=true
        val meter=ReadingSessionMeter(store,{eligible},{key},{elapsed})
        try {
            store.update {it.copy(statsEnabled=false,stats=it.stats-key)}
            meter.resume();elapsed+=5000;meter.pause();assertNull(store.state.value.stats[key])
            store.update {it.copy(statsEnabled=true)}
            meter.resume();elapsed+=7000;meter.pause();meter.pause()
            elapsed+=30_000
            meter.resume();elapsed+=3000;meter.pause()
            meter.complete("original-chapter");meter.complete("original-chapter")
            assertEquals(10_000L,store.state.value.stats[key]!!.foregroundMillis)
            assertEquals(1,store.state.value.stats[key]!!.completedChapters.size)
            eligible=false;meter.resume();elapsed+=9000;meter.pause();assertEquals(10_000L,store.state.value.stats[key]!!.foregroundMillis)
        } finally {store.update {original}}
    }
}
