package mihon.readingapp.books

import java.net.URI
import kotlin.math.abs

/** Summary of a native Readium position group, kept in publication reading order. */
internal data class EpubProgressResource(
    val href: String,
    val positionCount: Int,
    val firstPosition: Int?,
    val lastPosition: Int?,
    val positionsAreSequential: Boolean,
)

/** Whole-publication progression derived from Readium's real, resource-grouped EPUB positions. */
internal class EpubBookProgress private constructor(
    private val totalPositionCount: Int,
    private val byHref: Map<String, EpubResourceProgressBounds>,
) {
    fun progression(
        href: String,
        nativeTotalProgression: Double?,
        position: Int?,
        resourceProgression: Double?,
    ): Double? {
        val native = nativeTotalProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val bounds = byHref[resourceKey(href)] ?: return native
        val validResourceProgress = resourceProgression?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val nativeBucket = position
            ?.takeIf { it in 1..totalPositionCount }
            ?.let { (it - 1).toDouble() / totalPositionCount }
        val nativeIsDiscreteBucket = native != null && nativeBucket != null && abs(native - nativeBucket) < BUCKET_EPSILON

        // Readium's EPUB navigator carries the selected position's discrete book fraction while
        // its resource progression continues to move. Interpolate only that recognized bucket;
        // other valid native totals may be genuinely continuous and remain authoritative.
        if ((native == null || nativeIsDiscreteBucket) && validResourceProgress != null) {
            return (bounds.start + (bounds.end - bounds.start) * validResourceProgress).coerceIn(0.0, 1.0)
        }
        return native
    }

    private data class EpubResourceProgressBounds(val start: Double, val end: Double)

    companion object {
        private const val BUCKET_EPSILON = 1e-8

        fun fromReadingOrder(resources: List<EpubProgressResource>): EpubBookProgress {
            val total = resources.sumOf { it.positionCount.coerceAtLeast(0) }
            if (total <= 0) return EpubBookProgress(0, emptyMap())

            var offset = 0
            var positionsAreSequential = true
            val entries = linkedMapOf<String, EpubResourceProgressBounds>()
            resources.forEach { resource ->
                val count = resource.positionCount.coerceAtLeast(0)
                val key = resourceKey(resource.href)
                if (resource.positionCount < 0 || !resource.positionsAreSequential || key.isEmpty() || key in entries) {
                    positionsAreSequential = false
                } else if (count > 0) {
                    val start = offset.toDouble() / total
                    val end = (offset + count).toDouble() / total
                    if (resource.firstPosition != offset + 1 || resource.lastPosition != offset + count) {
                        positionsAreSequential = false
                    }
                    if (start in 0.0..1.0 && end in start..1.0) entries[key] = EpubResourceProgressBounds(start, end)
                    else positionsAreSequential = false
                }
                offset += count
            }

            // If a service does not return the actual ordered positions, do not infer boundaries
            // from list lengths that may not correspond to the position identifiers.
            return EpubBookProgress(total, if (positionsAreSequential) entries else emptyMap())
        }

        private fun resourceKey(href: String): String {
            val path = runCatching { URI(href).normalize().rawPath }.getOrNull()
                ?: href.substringBefore('#').substringBefore('?').substringAfter("://", href)
            return path.trimStart('/')
        }
    }
}
