package mihon.readingapp.library

/** Bounded identity helpers shared by the dispatcher and its JVM tests. */
object SmartOfflinePolicy {
    data class Candidate(
        val id: String,
        val number: Double?,
        val sourceOrder: Long,
        val read: Boolean,
        val downloaded: Boolean,
        val manuallyQueued: Boolean,
    )

    fun <T> fairWindow(values: List<T>, cursor: String?, limit: Int, key: (T) -> String): List<T> {
        require(limit > 0)
        if (values.isEmpty()) return emptyList()
        val start = cursor?.let { last -> values.indexOfFirst { key(it) > last }.takeIf { it >= 0 } } ?: 0
        return (values.drop(start) + values.take(start)).take(limit)
    }

    fun nextUnread(candidates: List<Candidate>, count: Int, newestFirstFallback: Boolean = false): List<Candidate> {
        require(count in setOf(5, 10))
        val numbers = candidates.mapNotNull { it.number }
        val chapterOrder = candidates.isNotEmpty() && numbers.size == candidates.size &&
            numbers.all { it.isFinite() && it > 0 } && numbers.distinct().size == numbers.size
        val ordered = if (chapterOrder) candidates.sortedWith(compareBy<Candidate> { it.number }.thenBy { it.sourceOrder })
        else if (newestFirstFallback) candidates.sortedWith(compareByDescending<Candidate> { it.sourceOrder }.thenBy { it.id })
        else candidates.sortedWith(compareBy<Candidate> { it.sourceOrder }.thenBy { it.id })
        return ordered
            .asSequence()
            .filterNot { it.read }
            .take(count)
            .toList()
    }

    fun mayDownload(
        seriesKind: String,
        chapterId: String,
        format: String,
        hidden: Boolean,
        alreadyDownloaded: Boolean,
        manuallyQueued: Boolean,
        ownedCopies: Map<String, SmartOwnedCopy>,
    ): Boolean {
        if (hidden || alreadyDownloaded || manuallyQueued) return false
        if (seriesKind !in setOf("manga", "novel")) return false
        return SmartOfflineStore.ownershipKey(format, chapterId) !in ownedCopies
    }
}
