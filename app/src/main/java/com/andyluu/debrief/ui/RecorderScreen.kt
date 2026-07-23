@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andyluu.debrief.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andyluu.debrief.data.AppSettings
import com.andyluu.debrief.recording.RecordingPauseReason
import com.andyluu.debrief.recording.RecordingPhase
import com.andyluu.debrief.recording.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class HomeTab { LIBRARY, RECORDER }

@Composable
internal fun HomeNavigationBar(
    selected: HomeTab,
    onLibrary: () -> Unit,
    onRecorder: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == HomeTab.LIBRARY,
            onClick = onLibrary,
            icon = { Icon(Icons.Default.LibraryMusic, null) },
            label = { Text("Library") },
            modifier = Modifier.semantics { contentDescription = "Library tab" },
        )
        NavigationBarItem(
            selected = selected == HomeTab.RECORDER,
            onClick = onRecorder,
            icon = { Icon(Icons.Default.Mic, null) },
            label = { Text("Record") },
            modifier = Modifier.semantics { contentDescription = "Record tab" },
        )
    }
}

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    settings: AppSettings,
    onStart: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecorderContent(
        state = state,
        folderLinked = settings.folderUri != null,
        onStart = onStart,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stop,
        onRetry = { settings.folderUri?.let(viewModel::retrySave) },
        onPickFolder = onPickFolder,
        onClearMessage = viewModel::clearMessage,
        onOpenLibrary = onOpenLibrary,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
internal fun RecorderContent(
    state: RecordingState,
    folderLinked: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onPickFolder: () -> Unit,
    onClearMessage: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var elapsedMs by remember(state.phase, state.elapsedBeforeRunningMs, state.runningSinceElapsedMs) {
        mutableLongStateOf(state.elapsedMs())
    }
    LaunchedEffect(state.phase, state.elapsedBeforeRunningMs, state.runningSinceElapsedMs) {
        while (isActive) {
            elapsedMs = state.elapsedMs()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Recorder", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        enabled = !state.isSessionActive,
                    ) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            HomeNavigationBar(
                selected = HomeTab.RECORDER,
                onLibrary = onOpenLibrary,
                onRecorder = {},
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            FolderStatus(
                linked = folderLinked,
                enabled = !state.isSessionActive && state.phase != RecordingPhase.SAVE_FAILED,
                onPickFolder = onPickFolder,
            )
            Spacer(Modifier.weight(1f))

            Text(
                when (state.phase) {
                    RecordingPhase.RECORDING -> "Recording"
                    RecordingPhase.PAUSED -> when (state.pauseReason) {
                        RecordingPauseReason.CALL -> "Paused for call"
                        RecordingPauseReason.STORAGE -> "Paused — storage low"
                        else -> "Paused"
                    }
                    RecordingPhase.PREPARING -> "Preparing"
                    RecordingPhase.FINALIZING -> "Saving"
                    RecordingPhase.RECOVERING -> "Recovering"
                    RecordingPhase.SAVE_FAILED -> "Save needs attention"
                    RecordingPhase.IDLE -> if (folderLinked) "Ready" else "Choose a folder"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatRecorderDuration(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            state.displayName?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(22.dp))
            RecorderLevel(
                amplitude = if (state.phase == RecordingPhase.RECORDING) state.amplitude else 0f,
                active = state.phase == RecordingPhase.RECORDING,
            )
            Spacer(Modifier.height(28.dp))

            when (state.phase) {
                RecordingPhase.IDLE -> IdleRecordButton(
                    folderLinked = folderLinked,
                    onStart = onStart,
                )
                RecordingPhase.RECORDING, RecordingPhase.PAUSED -> ActiveRecordingControls(
                    paused = state.phase == RecordingPhase.PAUSED,
                    pauseEnabled = state.pauseReason !in setOf(RecordingPauseReason.CALL, RecordingPauseReason.STORAGE),
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                )
                RecordingPhase.SAVE_FAILED -> SaveFailureControls(
                    folderLinked = folderLinked,
                    onRetry = onRetry,
                    onPickFolder = onPickFolder,
                )
                else -> CircularProgressIndicator(Modifier.size(58.dp))
            }

            Spacer(Modifier.height(24.dp))
            state.statusMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.phase == RecordingPhase.SAVE_FAILED) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    )
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                        if (state.phase == RecordingPhase.IDLE) {
                            TextButton(onClick = onClearMessage) { Text("Dismiss") }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Offline • 128 kbps AAC • screen-off recording supported",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun FolderStatus(linked: Boolean, enabled: Boolean, onPickFolder: () -> Unit) {
    OutlinedButton(onClick = onPickFolder, enabled = enabled) {
        Icon(Icons.Default.FolderOpen, null)
        Spacer(Modifier.width(8.dp))
        Text(if (linked) "Current recordings folder" else "Choose recordings folder")
    }
}

@Composable
private fun IdleRecordButton(folderLinked: Boolean, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onStart,
            modifier = Modifier.size(92.dp).semantics {
                contentDescription = if (folderLinked) "Start recording" else "Choose folder and start recording"
            },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(0xFFFFDAD6),
                contentColor = Color(0xFFD93025),
            ),
        ) {
            Box(
                Modifier.size(46.dp).background(Color(0xFFD93025), CircleShape),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(if (folderLinked) "Tap to record" else "Choose a folder to record")
    }
}

@Composable
private fun ActiveRecordingControls(
    paused: Boolean,
    pauseEnabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        FilledIconButton(
            onClick = if (paused) onResume else onPause,
            enabled = pauseEnabled,
            modifier = Modifier.size(68.dp).semantics {
                contentDescription = if (paused) "Resume recording" else "Pause recording"
            },
        ) {
            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(34.dp))
        }
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(78.dp).semantics { contentDescription = "Stop and save recording" },
        ) {
            Icon(Icons.Default.Stop, null, Modifier.size(38.dp), tint = Color(0xFFD93025))
        }
    }
}

@Composable
private fun SaveFailureControls(
    folderLinked: Boolean,
    onRetry: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetry, enabled = folderLinked) { Text("Retry save") }
        OutlinedButton(onClick = onPickFolder) { Text("Choose another folder") }
        Text(
            "The local recovery copy remains on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecorderLevel(amplitude: Float, active: Boolean) {
    var levels by remember { mutableStateOf(List(32) { 0f }) }
    LaunchedEffect(amplitude, active) {
        levels = (levels.drop(1) + if (active) amplitude.coerceIn(0.03f, 1f) else 0.03f)
    }
    val color = if (active) Color(0xFFD93025) else MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "Microphone level" }
    ) {
        val spacing = size.width / levels.size
        levels.forEachIndexed { index, level ->
            val barHeight = (size.height * (0.12f + level * 0.88f)).coerceAtMost(size.height)
            val x = spacing * index + spacing / 2
            drawLine(
                color = color,
                start = Offset(x, (size.height - barHeight) / 2),
                end = Offset(x, (size.height + barHeight) / 2),
                strokeWidth = (spacing * 0.42f).coerceAtLeast(2f),
                cap = StrokeCap.Round,
            )
        }
    }
}

internal fun formatRecorderDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
