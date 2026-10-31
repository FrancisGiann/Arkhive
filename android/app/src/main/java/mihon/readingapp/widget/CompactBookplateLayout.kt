package mihon.readingapp.widget

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry for the approved 2×2 bookplate, kept separate from the large and tight widgets. */
internal data class CompactBookplateMetrics(
    val scale: Float,
    val padding: Float,
    val mainHeight: Float,
    val coverWidth: Float,
    val coverHeight: Float,
    val copyLeft: Float,
    val copyWidth: Float,
    val titleTop: Float,
    val titleMaxHeight: Float,
    val maxTitleLines: Int,
    val ringDiameter: Float,
    val ringStroke: Float,
    val ringTop: Float,
    val buttonHeight: Float,
    val cornerRadius: Float,
)

internal data class CompactBookplateCopy(
    val title: String,
    val count: String?,
    val progress: Float?,
    val action: String,
    val contentDescription: String,
) {
    val hasProgress: Boolean get() = progress != null
}

internal fun compactBookplateMetrics(width: Int, height: Int, fontScale: Float = 1f): CompactBookplateMetrics {
    require(width > 0 && height > 0)
    val scale = min(1f, min(width / 180f, height / 180f))
    val smallLayout = width < 170 || height < 170
    val padding = if (smallLayout) 5f else 6f
    val horizontalGap = if (smallLayout) 6f else 7f
    val verticalGap = 4f
    val buttonHeight = if (smallLayout) 29f else 33f
    val mainHeight = height - padding * 2f - buttonHeight - verticalGap
    val coverWidth = (width - padding * 2f - horizontalGap).coerceAtLeast(1f) * (.94f / 1.94f)
    val coverHeight = mainHeight.coerceAtLeast(1f)
    val copyLeft = padding + coverWidth + horizontalGap
    val copyWidth = (width - padding - copyLeft).coerceAtLeast(1f)
    val ringDiameter = if (smallLayout) 28f else 32f
    val countBlockHeight = (12f * 1.1f + 2f + 8.5f) * scale
    val ringGap = if (smallLayout) 3f else 4f
    val ringTop = padding + mainHeight - ringDiameter - ringGap - countBlockHeight
    val titleTop = padding + 2f * scale
    val titleMaxHeight = (ringTop - 3f * scale - titleTop).coerceAtLeast(1f)
    val lineHeight = (14f * scale * 1.1f * fontScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
    val maxTitleLines = floor(titleMaxHeight / lineHeight).toInt().coerceIn(1, 5)
    return CompactBookplateMetrics(
        scale = scale,
        padding = padding,
        mainHeight = mainHeight,
        coverWidth = coverWidth,
        coverHeight = coverHeight,
        copyLeft = copyLeft,
        copyWidth = copyWidth,
        titleTop = titleTop,
        titleMaxHeight = titleMaxHeight,
        maxTitleLines = maxTitleLines,
        ringDiameter = ringDiameter,
        ringStroke = if (smallLayout) 3.5f else 4f,
        ringTop = ringTop,
        buttonHeight = buttonHeight,
        cornerRadius = if (smallLayout) 16f * scale else 16f,
    )
}

internal fun isTightCompactWidget(width: Int, height: Int, fontScale: Float): Boolean =
    width < 145 || height < 140 || fontScale > 1.3f

internal fun usesCompactBookplate(width: Int, height: Int, fontScale: Float): Boolean =
    !isTightCompactWidget(width, height, fontScale) &&
        width <= 220 && height <= 220 && (width.toFloat() / height).coerceAtLeast(0f) in .85f..1.15f

internal fun compactBookplateCopy(story: ContinueStory?, locked: Boolean): CompactBookplateCopy {
    val action = when {
        story != null -> "Resume reading"
        locked -> "Unlock Arkhive"
        else -> "Open library"
    }
    val title = story?.title ?: if (locked) "Your library is locked" else "Your next chapter"
    val progress = story?.progress?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat()
    val count = when {
        story == null || progress == null -> null
        story.key.startsWith("book:") -> "${(progress * 100f).roundToInt().coerceIn(0, 100)}%"
        story.key.startsWith("manga:") -> COUNT_PREFIX.find(story.status)?.value?.trim()
        story.key.startsWith("novel:") -> COUNT_PREFIX.find(story.status.substringBefore(" read"))?.value?.trim()
        else -> null
    }
    val description = story?.let {
        val detail = count?.let { value -> "$value read" } ?: it.status
        "$action ${it.title}. $detail"
    } ?: action
    return CompactBookplateCopy(title, count, progress, action, description)
}

private val COUNT_PREFIX = Regex("\\d[\\d,]*\\s*/\\s*\\d[\\d,]*")
