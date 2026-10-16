package mihon.readingapp.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mihon.domain.discovery.*
import kotlin.coroutines.coroutineContext

data class CatalogPagingState(
    val query: CatalogQuery = CatalogQuery(),
    val result: CatalogLoad? = null,
    val pages: List<CatalogFeed> = emptyList(),
    val nextPage: Int? = 1,
    val loading: Boolean = false,
    val error: String? = null,
    val endReason: String? = null,
) {
    val titles get() = result?.feed?.titles.orEmpty()
}

/** One request at a time, generation-checked commits, provider order and bounded retained pages. */
class CatalogPager(private val scope: CoroutineScope,
    private val load: suspend (CatalogQuery, Int, Boolean) -> CatalogLoad) {
    companion object { const val MAX_PAGES = 40 }
    private val mutable = MutableStateFlow(CatalogPagingState())
    val state: StateFlow<CatalogPagingState> = mutable
    private var job: Job? = null
    private var generation = 0L
    var scrollIndex = 0
    var scrollOffset = 0
    fun select(query: CatalogQuery, force: Boolean = false) {
        if (!force && mutable.value.query == query && (mutable.value.result != null || mutable.value.loading)) return
        job?.cancel(); generation++
        scrollIndex = 0; scrollOffset = 0
        mutable.value = CatalogPagingState(query)
        loadNext(force)
    }
    fun pause() { job?.cancel(); generation++; mutable.value = mutable.value.copy(loading = false) }
    fun retry() { if (!mutable.value.loading) { mutable.value = mutable.value.copy(error = null); loadNext(true) } }
    fun loadNext(force: Boolean = false) {
        val before = mutable.value
        val page = before.nextPage ?: return
        if (before.loading || before.error != null) return
        val token = generation
        mutable.value = before.copy(loading = true)
        job = scope.launch {
            try {
                val response = load(before.query, page, force)
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                val feed = response.feed
                if (feed == null) {
                    mutable.value = mutable.value.copy(loading = false, error = response.message ?: "This page could not be loaded.")
                    return@launch
                }
                check(feed.page == page) { "The provider returned a different page." }
                val previous = before.titles
                val known = previous.mapTo(hashSetOf()) { it.key }
                val added = feed.titles.filter { known.add(it.key) }
                val repeated = page > 1 && feed.titles.isNotEmpty() && added.isEmpty()
                val all = previous + added
                val next = if (repeated) null else feed.nextPage
                check(next == null || (next == page + 1 && next <= MAX_PAGES)) { "Unsupported provider continuation." }
                val pages = before.pages + feed
                val combined = feed.copy(titles = all, provenance = pages.first().provenance)
                mutable.value = before.copy(result = response.copy(feed = combined,
                    cached = response.cached || before.result?.cached == true,
                    stale = response.stale || before.result?.stale == true), pages = pages,
                    nextPage = next, loading = false, error = null,
                    endReason = when {
                        repeated -> "The provider repeated earlier results. Refresh to check for changes."
                        page == MAX_PAGES && next == null && feed.titles.size >= 24 -> "${all.size} results loaded. Refine your search to explore more."
                        next == null -> "End of this provider's results"
                        else -> null
                    })
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (token == generation) mutable.value = mutable.value.copy(loading = false, error = error.message ?: "This page could not be loaded.")
            }
        }
    }
}
