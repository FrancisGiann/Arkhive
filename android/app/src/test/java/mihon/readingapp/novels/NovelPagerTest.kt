package mihon.readingapp.novels

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelPagerTest {
    private fun source(load:suspend (Int,String,String?)->NovelCatalogPage)=object:NovelSource {
        override val id="fixture";override val name="Original fixture";override val site="https://fixture.invalid"
        override suspend fun catalog(page:Int,search:String,genre:String?)=load(page,search,genre)
        override suspend fun detail(path:String):SourceNovel=error("not needed")
        override suspend fun chapter(path:String):String=error("not needed")
    }
    private fun page(range:IntRange,next:Int?)=NovelCatalogPage(range.map {NovelItem("original-$it","Original $it")},next,"Original fixture order")
    @Test fun paginationDeduplicatesAndKeepsActualEnd()=runTest {
        val calls=mutableListOf<Int>();val pager=NovelCatalogPager(this,source {p,_,_->calls+=p;if(p==1) page(1..24,2) else page(24..30,null)})
        pager.select("",null);runCurrent();pager.loadNext();advanceUntilIdle();pager.loadNext();runCurrent()
        assertEquals(listOf(1,2),calls);assertEquals((1..30).map {"original-$it"},pager.state.value.items.map {it.path});assertNull(pager.state.value.next)
    }
    @Test fun nextPageFailureRetainsExistingRowsAndRetriesSamePage()=runTest {
        var fail=true;val calls=mutableListOf<Int>();val pager=NovelCatalogPager(this,source {p,_,_->calls+=p;if(p==1) page(1..24,2) else if(fail) error("controlled429") else page(25..27,null)})
        pager.select("",null);runCurrent();pager.loadNext();runCurrent();assertEquals(24,pager.state.value.items.size);assertNotNull(pager.state.value.error)
        fail=false;pager.retry();advanceUntilIdle();assertEquals(listOf(1,2,2),calls);assertEquals(27,pager.state.value.items.size)
    }
    @Test fun oldCancelledSearchNeverOverwritesCurrentQueryAndViewportResets()=runTest {
        val pager=NovelCatalogPager(this,source {_,q,_->if(q=="old") withContext(NonCancellable){delay(100)};page(if(q=="old") 1..2 else 30..31,null)})
        pager.select("old",null);runCurrent();pager.scrollIndex=15;pager.scrollOffset=80;val revision=pager.state.value.revision
        pager.select("new","Fantasy");advanceUntilIdle()
        assertEquals("new",pager.state.value.query);assertEquals(listOf("original-30","original-31"),pager.state.value.items.map {it.path})
        assertEquals(0,pager.scrollIndex);assertEquals(0,pager.scrollOffset);assertTrue(pager.state.value.revision>revision)
    }
    @Test fun backNavigationPreservesViewportButRepeatedPagesStop()=runTest {
        var calls=0;val pager=NovelCatalogPager(this,source {p,_,_->calls++;page(1..24,p+1)})
        pager.select("",null);runCurrent();pager.scrollIndex=9;pager.scrollOffset=42;pager.pause();pager.select("",null);runCurrent()
        assertEquals(1,calls);assertEquals(9,pager.scrollIndex);assertEquals(42,pager.scrollOffset)
        pager.loadNext();runCurrent();assertNull(pager.state.value.next);assertEquals(24,pager.state.value.items.size)
    }
    @Test fun invalidContinuationIsErrorAndEmptyPageDoesNotInventStories()=runTest {
        val bad=NovelCatalogPager(this,source {_,_,_->page(1..2,8)})
        bad.select("",null);runCurrent();assertNotNull(bad.state.value.error);assertTrue(bad.state.value.items.isEmpty())
        val empty=NovelCatalogPager(this,source {_,_,_->page(IntRange.EMPTY,null)})
        empty.select("",null);runCurrent();assertNull(empty.state.value.next);assertTrue(empty.state.value.items.isEmpty())
    }
}
