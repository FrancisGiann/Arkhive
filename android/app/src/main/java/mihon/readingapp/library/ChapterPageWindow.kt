package mihon.readingapp.library

/** A bounded, zero-based window over a chapter list. */
data class ChapterPageWindow(
    val pageIndex: Int,
    val pageCount: Int,
    val startIndex: Int,
    val endIndexExclusive: Int,
) {
    val itemCount: Int get() = endIndexExclusive - startIndex
}

data class ChapterPageSlice<T>(
    val window: ChapterPageWindow,
    val items: List<T>,
    val previousItem: T?,
    val hasMoreAfter: Boolean,
)

/** Compose search-job key that changes when a new list instance replaces the source snapshot. */
class ChapterListIdentity(private val snapshot: Any) {
    fun matches(candidate: Any): Boolean = snapshot === candidate

    override fun equals(other: Any?): Boolean = other is ChapterListIdentity && snapshot === other.snapshot

    override fun hashCode(): Int = System.identityHashCode(snapshot)
}

const val CHAPTER_LIST_PAGE_SIZE = 100

fun chapterPageWindow(itemCount: Int, requestedPageIndex: Int, pageSize: Int = CHAPTER_LIST_PAGE_SIZE): ChapterPageWindow {
    require(itemCount >= 0) { "Chapter count cannot be negative." }
    require(pageSize > 0) { "Chapter page size must be positive." }
    val pageCount = ((itemCount.toLong() + pageSize - 1) / pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pageIndex = if (pageCount == 0) 0 else requestedPageIndex.coerceIn(0, pageCount - 1)
    val startIndex = (pageIndex.toLong() * pageSize).coerceAtMost(itemCount.toLong()).toInt()
    val endIndexExclusive = (startIndex.toLong() + pageSize).coerceAtMost(itemCount.toLong()).toInt()
    return ChapterPageWindow(pageIndex, pageCount, startIndex, endIndexExclusive)
}

fun <T> chapterPageSlice(items: List<T>, window: ChapterPageWindow): ChapterPageSlice<T> {
    require(window.startIndex in 0..items.size && window.endIndexExclusive in window.startIndex..items.size) {
        "Chapter page window is outside the current list."
    }
    return ChapterPageSlice(
        window = window,
        items = items.subList(window.startIndex, window.endIndexExclusive),
        previousItem = if (window.startIndex > 0) items[window.startIndex - 1] else null,
        hasMoreAfter = window.endIndexExclusive < items.size,
    )
}

fun chapterMatchesSearch(title: String, chapterNumber: Double?, query: String): Boolean {
    val value = query.trim()
    return value.isEmpty() || title.contains(value, ignoreCase = true) ||
        chapterNumber?.toString()?.contains(value, ignoreCase = true) == true
}
