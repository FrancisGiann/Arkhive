package mihon.readingapp.library

import mihon.readingapp.novels.lnreader.LnReaderUnapprovedSourceOriginException
import mihon.readingapp.novels.lnreader.normalizePublicHttpsOrigin

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

    /**
     * Keep source-origin recovery tied to a real LNReader novel and the exact installed bundle
     * that raised the block. This records a review route only; it never changes trust.
     */
    fun originReview(
        seriesKey: String,
        expectedSourceId: String,
        failure: LnReaderUnapprovedSourceOriginException,
    ): SmartOfflineOriginReview? {
        val sourceId = failure.sourceId ?: return null
        val bundleSha256 = failure.bundleSha256 ?: return null
        if (sourceId != expectedSourceId || !sourceId.matches(Regex("lnreader\\.[a-f0-9]{32}"))) return null
        if (!seriesKey.startsWith("novel:") || !LibraryToolsPolicy.validKey(seriesKey)) return null
        if (!bundleSha256.matches(Regex("[a-f0-9]{64}"))) return null
        val installedAt = failure.installedAt ?: return null
        val enabled = failure.sourceEnabled ?: return null
        val approvedOrigins = failure.approvedOrigins ?: return null
        if (!enabled || installedAt < 0 || approvedOrigins.isEmpty() || approvedOrigins.size > 24) return null
        if (approvedOrigins.any { runCatching { normalizePublicHttpsOrigin(it) != it }.getOrDefault(true) }) return null
        val origin = runCatching { normalizePublicHttpsOrigin(failure.origin) }.getOrNull() ?: return null
        if (origin != failure.origin || origin in approvedOrigins) return null
        return SmartOfflineOriginReview(seriesKey, sourceId, bundleSha256, installedAt, enabled, approvedOrigins.toSet(), origin)
    }

    fun canReviewOrigin(
        review: SmartOfflineOriginReview,
        hiddenSeries: Set<String>,
        favoriteSourceId: String?,
        smartRuleEnabled: Boolean,
        installedEnabled: Boolean,
        installedBundleSha256: String?,
        installedAt: Long?,
        approvedOrigins: Set<String>?,
    ): Boolean = review.seriesKey !in hiddenSeries &&
        favoriteSourceId == review.sourceId && smartRuleEnabled && installedEnabled &&
        review.enabled && installedBundleSha256 == review.bundleSha256 && installedAt == review.installedAt &&
        approvedOrigins == review.approvedOrigins &&
        approvedOrigins != null &&
        review.origin !in approvedOrigins

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
