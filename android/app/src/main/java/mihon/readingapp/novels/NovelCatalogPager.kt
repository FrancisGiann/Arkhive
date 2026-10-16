package mihon.readingapp.novels

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NovelBrowseState(val query:String="",val genre:String?=null,val items:List<NovelItem> = emptyList(),
    val page:Int=0,val next:Int?=1,val busy:Boolean=false,val error:String?=null,val basis:String="",val checked:Long=0,val revision:Long=0)

/** Retained, bounded source order with cancellation and generation-checked commits. */
class NovelCatalogPager(private val scope:CoroutineScope,private val source:NovelSource) {
    private val mutable=MutableStateFlow(NovelBrowseState())
    val state:StateFlow<NovelBrowseState> = mutable
    @Volatile private var job:Job?=null
    @Volatile private var generation=0L
    var scrollIndex=0
    var scrollOffset=0
    fun select(query:String,genre:String?,force:Boolean=false) {
        if(!force && mutable.value.query==query && mutable.value.genre==genre && (mutable.value.checked>0 || mutable.value.busy)) return
        job?.cancel();generation++;scrollIndex=0;scrollOffset=0;mutable.value=NovelBrowseState(query,genre,revision=generation);loadNext()
    }
    fun pause() {job?.cancel();generation++;mutable.value=mutable.value.copy(busy=false)}
    /** Drop cached catalog rows and fence any in-flight result after trust or install changes. */
    fun invalidateForSourceChange() {
        val previous = mutable.value
        job?.cancel()
        job = null
        generation++
        scrollIndex = 0
        scrollOffset = 0
        mutable.value = NovelBrowseState(
            query = previous.query,
            genre = previous.genre,
            error = "This source changed or was disabled. Reopen it to load the current catalog.",
            revision = generation,
        )
    }
    fun retry() {mutable.value=mutable.value.copy(error=null);loadNext()}
    fun loadNext() {
        val before=mutable.value;val page=before.next ?: return
        if(before.busy || before.error!=null) return
        val token=generation;mutable.value=before.copy(busy=true)
        job=scope.launch {
            try {
                val result=source.catalog(page,before.query,before.genre)
                currentCoroutineContext().ensureActive();if(token!=generation) return@launch
                val known=before.items.mapTo(hashSetOf()){it.path};val added=result.items.filter {known.add(it.path)}
                val repeated=page>1 && result.items.isNotEmpty() && added.isEmpty()
                check(result.nextPage==null || result.nextPage==page+1) {"Unsupported source continuation."}
                mutable.value=before.copy(items=before.items+added,page=page,next=if(repeated || page>=40) null else result.nextPage,
                    busy=false,error=null,basis=result.basis,checked=System.currentTimeMillis())
            } catch(cancelled:CancellationException) {throw cancelled}
              catch(error:Exception) {if(token==generation) mutable.value=mutable.value.copy(busy=false,error=error.message ?: "This source is unavailable.")}
        }
    }
}
