package eu.kanade.tachiyomi.ui.reader.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow

/** Accessible in-flow playback control for readers whose chrome can be hidden. */
@Composable
fun ReaderAutomationStripControl(
    state: ReaderAutomationUiState,
    canStart: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
) {
    val canResume = canStart &&
        (state.settings.mode != ReaderAutomationMode.CONTINUOUS || state.continuousAvailable)
    val resolvedContentColor = statusColor ?: LocalContentColor.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (state.running) "Auto read running" else "Auto read paused",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = resolvedContentColor.copy(alpha = 0.78f),
        )
        IconButton(
            onClick = if (state.running) onPause else onStart,
            enabled = state.running || canResume,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = when {
                        state.running -> "Pause auto read"
                        canResume -> "Resume auto read"
                        else -> "Auto read unavailable; open Auto read options"
                    }
                    stateDescription = when {
                        state.running -> "Running"
                        canResume -> "Paused"
                        else -> "Paused; requirements are not met"
                    }
                },
        ) {
            Icon(
                imageVector = if (state.running) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow,
                contentDescription = null,
                tint = resolvedContentColor,
            )
        }
    }
}

/** One compact control sheet; the playback chip remains available while the reader is active. */
@Composable
fun ReaderAutomationControls(
    state: ReaderAutomationUiState,
    menuVisible: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onSettingsChange: (ReaderAutomationSettings) -> Unit,
    onSettingsPreview: (ReaderAutomationSettings) -> Unit,
    onSettingsChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    continuousUnavailableMessage: String = "Requires a webtoon/vertical strip reader and enabled system motion. Disabled during touch exploration.",
    continuousTransitionDescription: String? = null,
    onContinuousTransition: (() -> Unit)? = null,
    timedStepDescription: String = "Manga advances one page in its reading direction. Webtoons move up to three quarters of a screen; motion follows system settings.",
) {
    if (menuVisible) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Only this chapter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Touch, keys, zoom or leaving the reader pause playback. Reopening never starts it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModeOption(
                label = "Continuous scroll",
                selected = state.settings.mode == ReaderAutomationMode.CONTINUOUS,
                enabled = state.continuousAvailable || onContinuousTransition != null,
                onClick = {
                    if (state.continuousAvailable) {
                        onSettingsChange(state.settings.copy(mode = ReaderAutomationMode.CONTINUOUS))
                    } else {
                        onContinuousTransition?.invoke()
                    }
                },
            )
            if (!state.continuousAvailable) {
                Text(
                    continuousTransitionDescription ?: continuousUnavailableMessage,
                    modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModeOption(
                label = "Timed page / screen steps",
                selected = state.settings.mode == ReaderAutomationMode.TIMED_STEP,
                onClick = { onSettingsChange(state.settings.copy(mode = ReaderAutomationMode.TIMED_STEP)) },
            )
            if (state.settings.mode == ReaderAutomationMode.CONTINUOUS) {
                Text("Scroll speed · ${state.settings.speedDpPerSecond} / 240", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.settings.speedDpPerSecond.toFloat(),
                    onValueChange = {
                        onSettingsPreview(state.settings.copy(speedDpPerSecond = it.roundToInt()))
                    },
                    onValueChangeFinished = onSettingsChangeFinished,
                    valueRange = 16f..240f,
                    modifier = Modifier.semantics { contentDescription = "Continuous scroll speed" },
                )
            } else {
                Text("Advance every ${state.settings.intervalSeconds} seconds", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.settings.intervalSeconds.toFloat(),
                    onValueChange = {
                        onSettingsPreview(state.settings.copy(intervalSeconds = it.roundToInt()))
                    },
                    onValueChangeFinished = onSettingsChangeFinished,
                    valueRange = 2f..60f,
                    steps = 57,
                    modifier = Modifier.semantics { contentDescription = "Page or screen step interval" },
                )
                Text(timedStepDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                Button(
                    onClick = if (state.running) onPause else onStart,
                    enabled = state.running || canStart,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(if (state.running) "Pause auto read" else "Start auto read", fontWeight = FontWeight.Bold)
                }
            }
        }
    } else if (state.running) {
        Surface(
            modifier = modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 4.dp,
        ) {
            Button(
                onClick = onPause,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("Pause auto read", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
