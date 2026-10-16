package mihon.readingapp.discovery

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import mihon.domain.discovery.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogPagerTest {
    private fun page(number: Int, keys: IntRange, next: Int?) = CatalogLoad(CatalogFeed(keys.map {
        CatalogTitle("/works/OL${it}W", "Original catalogue fixture $it", emptyList(), emptyList(), null, null, emptyList())
    }, CatalogProvenance("Controlled fixture", "https://fixture.invalid/?page=$number", "Fixture order", null, number.toLong(), null), null, number, next))
    @Test fun overlappingPagesKeepStableProviderOrderAndActualEnd() = runTest {
        val requests = mutableListOf<Int>()
        val pager = CatalogPager(this) { _, p, _ -> requests += p; if (p == 1) page(1, 1..24, 2) else page(2, 24..30, null) }
        pager.select(CatalogQuery()); runCurrent(); pager.loadNext(); advanceUntilIdle()
        assertEquals(listOf(1, 2), requests); assertEquals((1..30).map { "/works/OL${it}W" }, pager.state.value.titles.map { it.key })
        assertNull(pager.state.value.nextPage); assertEquals(2, pager.state.value.pages.size)
        pager.loadNext(); runCurrent(); assertEquals(2, requests.size)
    }
    @Test fun failedNextPagePreservesResultsAndRetriesThatPage() = runTest {
        var failed = true
        val requests = mutableListOf<Int>()
        val pager = CatalogPager(this) { _, p, _ -> requests += p; when { p == 1 -> page(1, 1..24, 2); failed -> CatalogLoad(null, message = "Controlled HTTP429"); else -> page(2, 25..26, null) } }
        pager.select(CatalogQuery()); runCurrent(); pager.loadNext(); runCurrent()
        assertEquals(24, pager.state.value.titles.size); assertNotNull(pager.state.value.error)
        pager.loadNext(); runCurrent(); assertEquals(listOf(1, 2), requests)
        failed = false; pager.retry(); advanceUntilIdle(); assertEquals(listOf(1, 2, 2), requests); assertEquals(26, pager.state.value.titles.size)
    }
    @Test fun aRepeatedFirstPageStopsInsteadOfAnInfiniteLoop() = runTest {
        val pager = CatalogPager(this) { _, p, _ -> page(p, 1..24, p + 1) }
        pager.select(CatalogQuery()); runCurrent(); pager.loadNext(); runCurrent()
        assertNull(pager.state.value.nextPage); assertEquals(24, pager.state.value.titles.size)
        assertTrue(pager.state.value.endReason!!.contains("repeated"))
    }
    @Test fun lateCancelledFilterResultsNeverOverwriteNewSelection() = runTest {
        val pager = CatalogPager(this) { q, p, _ ->
            if (q.search == "old") withContext(NonCancellable) { delay(100) }
            page(p, if(q.search == "old") 1..2 else 50..51, null)
        }
        pager.select(CatalogQuery(search = "old")); runCurrent()
        pager.select(CatalogQuery(search = "new")); advanceUntilIdle()
        assertEquals("new", pager.state.value.query.search); assertEquals(listOf("/works/OL50W", "/works/OL51W"), pager.state.value.titles.map { it.key })
    }
    @Test fun returningToSameQueryRetainsPagesAndViewport() = runTest {
        var calls = 0
        val pager = CatalogPager(this) { _, p, _ -> calls++; page(p, 1..24, 2) }
        val q = CatalogQuery(); pager.select(q); runCurrent(); pager.scrollIndex = 12; pager.scrollOffset = 91
        pager.pause(); pager.select(q); runCurrent()
        assertEquals(1, calls); assertEquals(12, pager.scrollIndex); assertEquals(91, pager.scrollOffset)
    }
    @Test fun emptyFirstPageEndsWithoutInventedTitles() = runTest {
        val pager = CatalogPager(this) { _, p, _ -> page(p, IntRange.EMPTY, null) }
        pager.select(CatalogQuery()); runCurrent(); assertTrue(pager.state.value.titles.isEmpty()); assertNull(pager.state.value.nextPage); assertNull(pager.state.value.error)
    }
}
