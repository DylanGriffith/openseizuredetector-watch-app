package uk.org.openseizuredetector.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import uk.org.openseizuredetector.presentation.theme.OpenSeizureDetectorWatchTheme
import uk.org.openseizuredetector.service.AlarmStates
import uk.org.openseizuredetector.service.SensorDataService
import uk.org.openseizuredetector.service.WatchUiState

@Composable
fun OsdApp(service: SensorDataService?) {
    OpenSeizureDetectorWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            if (service == null) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    text = "Starting…"
                )
            } else {
                val state by service.uiState.collectAsState()
                OsdScreen(
                    state = state,
                    onPause = service::pauseAlarms,
                    onCancelPause = service::cancelPause,
                    onDismiss = service::dismissAlarm,
                )
            }
            TimeText()
        }
    }
}

@Composable
private fun OsdScreen(
    state: WatchUiState,
    onPause: () -> Unit,
    onCancelPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Ticking clock so the pause countdown updates once a second
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.pausedUntilMillis) {
        while (state.pausedUntilMillis > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    val paused = state.pausedUntilMillis > now

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { StatusBanner(state) }

        item {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground,
                text = buildString {
                    append("♥ ")
                    append(if (state.heartRate > 0) "${state.heartRate} bpm" else "--")
                    append("   🔋 ")
                    append(if (state.batteryPc >= 0) "${state.batteryPc}%" else "--")
                }
            )
        }

        item { LatencyText(state) }

        if (state.alarmState in AlarmStates.ALARMING || state.alarmState == AlarmStates.WARNING) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = Color(0xFFC62828),
                        contentColor = Color.White,
                    ),
                    label = {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            text = "DISMISS\nFALSE ALARM"
                        )
                    },
                    onClick = onDismiss,
                )
            }
        }

        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                label = {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = if (paused) {
                            "Paused ${formatCountdown(state.pausedUntilMillis - now)}\nTap to resume"
                        } else {
                            "Pause alarms 1h"
                        }
                    )
                },
                onClick = if (paused) onCancelPause else onPause,
            )
        }
    }
}

@Composable
private fun LatencyText(state: WatchUiState) {
    val latencyMs = state.lastAlarmLatencyMs
    val ageMs = state.lastAlarmStateAgeMs
    val color = when {
        latencyMs >= 120_000 || ageMs >= 120_000 -> Color(0xFFC62828)
        latencyMs >= 30_000 || ageMs >= 30_000 -> Color(0xFFFFA000)
        else -> MaterialTheme.colors.onBackground
    }
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        textAlign = TextAlign.Center,
        color = color,
        text = buildString {
            append("Delay ")
            append(formatDuration(latencyMs))
            append("   Age ")
            append(formatDuration(ageMs))
        }
    )
}

@Composable
private fun StatusBanner(state: WatchUiState) {
    val (color, label) = when {
        !state.phoneConnected -> Color(0xFF616161) to "NO PHONE"
        state.alarmState in AlarmStates.ALARMING -> Color(0xFFC62828) to stateLabel(state)
        state.alarmState == AlarmStates.WARNING -> Color(0xFFFFA000) to stateLabel(state)
        state.alarmState == AlarmStates.MUTE -> Color(0xFF1565C0) to stateLabel(state)
        state.alarmState == AlarmStates.OK -> Color(0xFF2E7D32) to stateLabel(state)
        state.alarmState == AlarmStates.FAULT ||
                state.alarmState == AlarmStates.NETFAULT -> Color(0xFFEF6C00) to stateLabel(state)
        else -> Color(0xFF616161) to "WAITING…"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            text = label,
        )
    }
}

private fun stateLabel(state: WatchUiState): String =
    state.alarmPhrase.ifBlank { AlarmStates.name(state.alarmState) }

private fun formatCountdown(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun formatDuration(millis: Long): String {
    if (millis < 0) return "--"
    val totalSec = (millis / 1000).coerceAtLeast(0)
    return if (totalSec < 60) {
        "${totalSec}s"
    } else {
        "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
