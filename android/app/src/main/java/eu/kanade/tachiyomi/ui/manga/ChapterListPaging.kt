package eu.kanade.tachiyomi.ui.manga

import kotlin.math.floor
import tachiyomi.domain.chapter.service.calculateChapterGap

/** Returns the missing-count label at a global or inter-page boundary. */
internal fun missingChapterCountBefore(
    beforeNumber: Double?,
    afterNumber: Double?,
    descending: Boolean,
    firstPage: Boolean,
    hasMoreAfter: Boolean = false,
): Int? {
    if (afterNumber == null) {
        if (!descending || hasMoreAfter || beforeNumber == null) return null
        return (floor(beforeNumber).toInt() - 1).takeIf { it > 0 }
    }
    val (lower, higher) = if (descending) afterNumber to beforeNumber else beforeNumber to afterNumber
    if (higher == null) return null
    val count = if (lower == null) {
        if (!firstPage || descending) return null
        floor(higher).toInt() - 1
    } else {
        calculateChapterGap(higher, lower)
    }
    return count.takeIf { it > 0 }
}
