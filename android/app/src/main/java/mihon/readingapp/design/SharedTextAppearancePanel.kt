package mihon.readingapp.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import mihon.readingapp.library.ReadingRoom

/** A compact, paper-matched sample that remains visible while the controls scroll. */
@Composable
internal fun SharedTextAppearancePreview(
    fontPercent: Float,
    lineHeight: Float,
    margins: Float,
    effectiveRoom: ReadingRoom,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        color = Color(effectiveRoom.paper),
        contentColor = Color(effectiveRoom.text),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            "Quiet pages.\nRead a little.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = (8f * margins).dp, vertical = 6.dp),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontSize = (14f * fontPercent / 100f).sp,
            lineHeight = (14f * fontPercent / 100f * lineHeight).sp,
            maxLines = 2,
        )
    }
}

/** Shared live controls for reflowable EPUB and online-text readers. */
@Composable
internal fun SharedTextAppearancePanel(
    layout: String?,
    onLayoutSelected: (String) -> Unit,
    fontPercent: Float,
    onFontPreview: (Float) -> Unit,
    onFontSettled: () -> Unit,
    lineHeight: Float,
    onLineHeightPreview: (Float) -> Unit,
    onLineHeightSettled: () -> Unit,
    margins: Float,
    onMarginsPreview: (Float) -> Unit,
    onMarginsSettled: () -> Unit,
    effectiveRoom: ReadingRoom,
    onRoomSelected: (ReadingRoom) -> Unit,
    supportsLayout: Boolean,
    supportsLineHeight: Boolean = true,
    supportsMargins: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (supportsLayout) {
            Text("Reading layout", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onLayoutSelected("pages") }) {
                    Text(if (layout == "pages") "Pages ✓" else "Pages")
                }
                TextButton(onClick = { onLayoutSelected("scroll") }) {
                    Text(if (layout == "scroll") "Vertical scroll ✓" else "Vertical scroll")
                }
            }
        }

        Text("Text size: ${fontPercent.toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Slider(
            value = fontPercent,
            onValueChange = onFontPreview,
            valueRange = 80f..220f,
            onValueChangeFinished = onFontSettled,
            modifier = Modifier.semantics { contentDescription = "Text size" },
        )

        if (supportsLineHeight) {
            Text("Line spacing: ${"%.1f".format(lineHeight)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Slider(
                value = lineHeight,
                onValueChange = onLineHeightPreview,
                valueRange = 1f..2.5f,
                onValueChangeFinished = onLineHeightSettled,
                modifier = Modifier.semantics { contentDescription = "Line spacing" },
            )
        }

        if (supportsMargins) {
            Text("Page margins: ${"%.1f".format(margins)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Slider(
                value = margins,
                onValueChange = onMarginsPreview,
                valueRange = .5f..2.5f,
                onValueChangeFinished = onMarginsSettled,
                modifier = Modifier.semantics { contentDescription = "Page margins" },
            )
        }

        Text("Reading paper · ${effectiveRoom.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReadingRoom.values().forEach { room ->
                FilterChip(
                    selected = room == effectiveRoom,
                    onClick = { onRoomSelected(room) },
                    label = { Text(room.label, maxLines = 1) },
                )
            }
        }
    }
}
