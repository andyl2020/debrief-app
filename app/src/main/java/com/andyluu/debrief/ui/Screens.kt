@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andyluu.debrief.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.andyluu.debrief.BuildConfig
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.RepairEntity
import com.andyluu.debrief.data.RepairRunEntity
import com.andyluu.debrief.data.RepairRunStatus
import com.andyluu.debrief.data.SearchHit
import com.andyluu.debrief.data.SuspectSpanEntity
import com.andyluu.debrief.data.TranscriptQualityReportEntity
import com.andyluu.debrief.data.TranscriptQualityStatus
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptionAudioQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    onPickFolder: () -> Unit,
    onOpenRecording: (String, Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestTranscription: (Collection<String>) -> Unit,
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val aiRecordings by viewModel.aiRecordings.collectAsStateWithLifecycle()
    val aiByRecording = aiRecordings.associateBy { it.recordingId }
    val repairRuns by viewModel.repairRuns.collectAsStateWithLifecycle()
    val repairRunByRecording = repairRuns.groupBy { it.recordingId }.mapValues { entry -> entry.value.maxBy { it.createdAt } }
    val qualityReports by viewModel.qualityReports.collectAsStateWithLifecycle()
    val qualityByRecording = qualityReports.associateBy { it.recordingId }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectableIds = recordings.filter { it.isTranscribable() }.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(selectableIds) { selectedIds = selectedIds.intersect(selectableIds) }
    val selectionMode = selectedIds.isNotEmpty()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (selectionMode) "${selectedIds.size} selected" else "Debrief", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (selectionMode) IconButton(onClick = { selectedIds = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
                    else IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, "Search all") }
                },
                actions = {
                    if (!selectionMode) IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        if (settings.folderUri == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text("Link your recordings folder", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Debrief reads audio in place. Your originals stay in the folder you choose.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onPickFolder) { Text("Choose folder") }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPickFolder) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Change") }
                    Button(
                        onClick = {
                            val selected = selectedIds
                            selectedIds = emptySet()
                            onRequestTranscription(selected)
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text(if (selectedIds.isEmpty()) "Transcribe selected" else "Transcribe (${selectedIds.size})") }
                }
                if (!selectionMode && selectableIds.isNotEmpty()) {
                    Text(
                        "Press and hold a recording to select several.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val hasTranscriptionKey = if (settings.provider == "assemblyai") {
                    viewModel.hasAssemblyAiKey()
                } else {
                    viewModel.hasDeepgramKey()
                }
                if (!hasTranscriptionKey) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onOpenSettings),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        val label = if (settings.provider == "assemblyai") "AssemblyAI" else "Deepgram"
                        Text("Add your $label key in Settings before transcribing.", Modifier.padding(14.dp))
                    }
                }
                if (recordings.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No MP3, M4A, WAV, or AAC recordings found.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recordings, key = { it.id }) { recording ->
                            RecordingCard(
                                recording = recording,
                                ai = aiByRecording[recording.id],
                                repairRun = if (settings.aiEnhanceEnabled) repairRunByRecording[recording.id] else null,
                                quality = qualityByRecording[recording.id],
                                selectionMode = selectionMode,
                                selected = recording.id in selectedIds,
                                onOpen = { onOpenRecording(recording.id, 0) },
                                onToggleSelection = {
                                    if (recording.isTranscribable()) {
                                        selectedIds = if (recording.id in selectedIds) selectedIds - recording.id else selectedIds + recording.id
                                    }
                                },
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordingCard(
    recording: RecordingEntity,
    ai: AiRecordingEntity? = null,
    repairRun: RepairRunEntity? = null,
    quality: TranscriptQualityReportEntity? = null,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val selectable = recording.isTranscribable()
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).combinedClickable(
            onClick = { if (selectionMode) { if (selectable) onToggleSelection() } else onOpen() },
            onLongClick = { if (selectable) onToggleSelection() },
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { if (selectable) onToggleSelection() },
                        enabled = selectable,
                        modifier = Modifier.semantics { contentDescription = "Select ${recording.displayName}" },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(recording.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatTimestamp(recording.durationMs)}  •  ${formatSize(recording.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(recording.status)
            }
            if (recording.status == RecordingStatus.READY && quality != null) {
                QualityChip(quality, modifier = Modifier.padding(top = 10.dp))
            }
            repairRun?.let { EnhanceMiniStatus(it) }
            if (recording.status == RecordingStatus.TRANSCRIBING || recording.status == RecordingStatus.QUEUED) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            recording.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ai?.summary?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EnhanceMiniStatus(run: RepairRunEntity) {
    val label = when (run.status) {
        RepairRunStatus.RUNNING, RepairRunStatus.QUEUED -> "Enhancing: ${run.stageLabel}"
        RepairRunStatus.PARTIAL -> "Enhance paused: resume available"
        RepairRunStatus.FAILED -> "Enhance failed"
        RepairRunStatus.READY -> "Enhance ready: ${run.fixedCount} fixed"
    }
    val color = when (run.status) {
        RepairRunStatus.READY -> Color(0xFFD5F5DF)
        RepairRunStatus.PARTIAL, RepairRunStatus.FAILED -> Color(0xFFFFDAD6)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Text(
        label,
        Modifier.padding(top = 10.dp).background(color, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QualityChip(report: TranscriptQualityReportEntity, modifier: Modifier = Modifier) {
    val (label, color) = when (report.status) {
        TranscriptQualityStatus.GOOD -> "Quality good" to Color(0xFFD5F5DF)
        TranscriptQualityStatus.CHECK -> "Check transcript" to Color(0xFFFFF3CD)
        TranscriptQualityStatus.ISSUE -> "Possible issue" to Color(0xFFFFDAD6)
    }
    Text(
        label,
        modifier.background(color, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TranscriptQualityCard(
    report: TranscriptQualityReportEntity,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, color) = when (report.status) {
        TranscriptQualityStatus.GOOD -> "Transcript quality: Good" to Color(0xFFD5F5DF)
        TranscriptQualityStatus.CHECK -> "Transcript quality: Check transcript" to Color(0xFFFFF3CD)
        TranscriptQualityStatus.ISSUE -> "Transcript quality: Possible issue" to Color(0xFFFFDAD6)
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                "${providerLabel(report.provider)} · ${uploadQualityLabel(report.uploadMode)} upload · ${formatTimestamp(report.audioDurationMs)} audio · ${speakerCountLabel(report.speakerCount)}",
                style = MaterialTheme.typography.bodySmall,
            )
            report.warningsText.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDetails) { Text("View details") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun TranscriptQualityDetailsDialog(
    report: TranscriptQualityReportEntity,
    onDismiss: () -> Unit,
) {
    val warnings = report.warningsText.lineSequence().filter(String::isNotBlank).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transcript Quality") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider", fontWeight = FontWeight.SemiBold)
                Text(providerLabel(report.provider))
                Text("Upload mode", fontWeight = FontWeight.SemiBold)
                Text(uploadQualityLabel(report.uploadMode))
                Text("Recording length", fontWeight = FontWeight.SemiBold)
                Text(formatTimestamp(report.audioDurationMs))
                Text("Transcript coverage", fontWeight = FontWeight.SemiBold)
                Text(transcriptCoverageLabel(report))
                Text("Transcript size", fontWeight = FontWeight.SemiBold)
                Text("${report.segmentCount} segments · ${report.wordCount} words · ${"%.1f".format(report.wordsPerMinute)} words/min")
                Text("Speakers", fontWeight = FontWeight.SemiBold)
                Text(speakerCountLabel(report.speakerCount))
                Text("Warnings", fontWeight = FontWeight.SemiBold)
                if (warnings.isEmpty()) {
                    Text("No integrity issues found.")
                } else {
                    warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (report.recommendation.isNotBlank()) {
                    Text("Recommended action", fontWeight = FontWeight.SemiBold)
                    Text(report.recommendation, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun providerLabel(provider: String): String = when (provider) {
    "assemblyai" -> "AssemblyAI"
    "deepgram" -> "Deepgram"
    else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun uploadQualityLabel(value: String): String = when (value) {
    TranscriptionAudioQuality.ORIGINAL.storedValue -> "Original"
    TranscriptionAudioQuality.BALANCED.storedValue -> "Balanced"
    TranscriptionAudioQuality.DATA_SAVER.storedValue -> "Data saver"
    else -> value
}

private fun speakerCountLabel(count: Int): String = "$count " + if (count == 1) "speaker detected" else "speakers detected"

private fun transcriptCoverageLabel(report: TranscriptQualityReportEntity): String {
    val start = report.transcriptStartMs
    val end = report.transcriptEndMs
    return if (start == null || end == null) "No timed transcript coverage"
    else "${formatTimestamp(start)}-${formatTimestamp(end)} covered"
}

private fun RecordingEntity.isTranscribable() = status == RecordingStatus.NEW || status == RecordingStatus.FAILED

@Composable
private fun StatusChip(status: RecordingStatus) {
    val (label, color) = when (status) {
        RecordingStatus.NEW -> "New" to MaterialTheme.colorScheme.surfaceVariant
        RecordingStatus.QUEUED -> "Queued" to MaterialTheme.colorScheme.primaryContainer
        RecordingStatus.TRANSCRIBING -> "Transcribing" to MaterialTheme.colorScheme.primaryContainer
        RecordingStatus.READY -> "Ready" to Color(0xFFD5F5DF)
        RecordingStatus.FAILED -> "Retry" to Color(0xFFFFDAD6)
    }
    Text(label, Modifier.background(color, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
}

@Composable
fun GlobalSearchScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenHit: (SearchHit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SearchHit>()) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(query) {
        delay(250)
        results = if (query.isBlank()) emptyList() else viewModel.search(query)
    }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Search everything") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Transcript or comment") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(results) { hit -> SearchHitCard(hit) { onOpenHit(hit) } }
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("${hit.recordingName}  •  ${formatTimestamp(hit.timestampMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(hit.snippet.replace("[", "").replace("]", ""), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (hit.isComment) Text("Comment", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val aiLocalUsage = viewModel.aiUsage(settings.aiProvider)
    var deepgram by remember { mutableStateOf("") }
    var assembly by remember { mutableStateOf("") }
    var gemini by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf("") }
    var anthropicKey by remember { mutableStateOf("") }
    var openAiBaseUrl by remember(settings.openAiBaseUrl) { mutableStateOf(settings.openAiBaseUrl) }
    var openAiModel by remember(settings.openAiModel) { mutableStateOf(settings.openAiModel) }
    var anthropicModel by remember(settings.anthropicModel) { mutableStateOf(settings.anthropicModel) }
    var keyterms by remember(settings.keyterms) { mutableStateOf(settings.keyterms) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(settings.provider) { viewModel.refreshUsage(settings.provider) }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Transcription provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Recommended for noisy field recordings: AssemblyAI with Original upload. Deepgram remains available as a fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.provider == "assemblyai", onClick = { viewModel.setProvider("assemblyai") }, label = { Text("AssemblyAI · recommended") })
                    FilterChip(selected = settings.provider == "deepgram", onClick = { viewModel.setProvider("deepgram") }, label = { Text("Deepgram Nova-3") })
                }
            }
            item {
                SecretField(
                    label = if (viewModel.hasDeepgramKey()) "Deepgram key saved securely" else "Deepgram API key",
                    value = deepgram,
                    onChange = { deepgram = it },
                    onSave = {
                        viewModel.saveDeepgramKey(deepgram) { saved -> if (saved) deepgram = "" }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasAssemblyAiKey()) "AssemblyAI key saved securely" else "AssemblyAI API key",
                    value = assembly,
                    onChange = { assembly = it },
                    onSave = {
                        viewModel.saveAssemblyAiKey(assembly) { saved -> if (saved) assembly = "" }
                    },
                )
            }
            item { Text("Transcription audio quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Original audio usually gives speech models the most information. Compressed modes reduce upload size but can lose quiet speech details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AudioQualitySelector(
                    selected = settings.transcriptionAudioQuality,
                    onSelected = viewModel::setTranscriptionAudioQuality,
                )
            }
            item { HorizontalDivider() }
            item { Text("AI tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Organize Recording now handles summaries, speaker suggestions, and rename ideas from transcript text. Sets are manual so AI reruns cannot overwrite your boundaries. AI Enhance is Advanced/Experimental because AssemblyAI + Original upload is the recommended quality path.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.aiProvider == "gemini", onClick = { viewModel.setAiProvider("gemini") }, label = { Text("Gemini") })
                    FilterChip(selected = settings.aiProvider == "openai", onClick = { viewModel.setAiProvider("openai") }, label = { Text("Compatible") })
                    FilterChip(selected = settings.aiProvider == "anthropic", onClick = { viewModel.setAiProvider("anthropic") }, label = { Text("Claude") })
                }
            }
            item {
                SecretField(
                    label = if (viewModel.hasGeminiKey()) "Gemini key saved securely" else "Gemini API key",
                    value = gemini,
                    onChange = { gemini = it },
                    onSave = {
                        viewModel.saveGeminiKey(gemini) { saved -> if (saved) gemini = "" }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasOpenAiKey()) "Compatible key saved securely" else "OpenAI-compatible API key",
                    value = openAiKey,
                    onChange = { openAiKey = it },
                    onSave = {
                        viewModel.saveOpenAiKey(openAiKey) { saved -> if (saved) openAiKey = "" }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasAnthropicKey()) "Anthropic key saved securely" else "Anthropic API key",
                    value = anthropicKey,
                    onChange = { anthropicKey = it },
                    onSave = {
                        viewModel.saveAnthropicKey(anthropicKey) { saved -> if (saved) anthropicKey = "" }
                    },
                )
            }
            if (settings.aiProvider == "openai") {
                item {
                    OutlinedTextField(
                        value = openAiBaseUrl,
                        onValueChange = { openAiBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("HTTPS base URL") },
                        placeholder = { Text("https://provider.example/v1") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = openAiModel,
                        onValueChange = { openAiModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model name") },
                        singleLine = true,
                    )
                }
            }
            if (settings.aiProvider == "anthropic") {
                item {
                    OutlinedTextField(
                        value = anthropicModel,
                        onValueChange = { anthropicModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Claude model") },
                        singleLine = true,
                    )
                }
            }
            item {
                Button(onClick = {
                    viewModel.saveAiEndpoint(openAiBaseUrl, openAiModel, anthropicModel)
                }) { Text("Save AI settings") }
            }
            aiLocalUsage?.let { local ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AI key usage on this device", fontWeight = FontWeight.SemiBold)
                            Text("Key ID ${local.keyId}", style = MaterialTheme.typography.labelSmall)
                            Text("${local.successfulRequests} successful AI passes · ${formatUsageDuration(local.audioDurationMs)} of transcripts")
                            Text("Provider account totals are not exposed consistently; this counter follows the saved key locally.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(
                    "Gemini's free tier may use inputs and outputs to improve Google's models. Use Skip AI Pass on a recording or select another provider for sensitive transcripts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HorizontalDivider() }
            item { Text("Advanced / Experimental", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show AI Enhance tools", fontWeight = FontWeight.SemiBold)
                        Text("Off by default. Enables rough-spot cards, scrubber heat ticks, Cleaned view, and the Enhance button.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.aiEnhanceEnabled, onCheckedChange = viewModel::setAiEnhanceEnabled)
                }
            }
            if (settings.aiEnhanceEnabled) {
                item {
                    Text(
                        "AI Enhance repairs rough transcript spots with conservative text edits and optional short Gemini audio clips. Full recordings are never sent to Gemini.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Run automatically", fontWeight = FontWeight.SemiBold)
                            Text("Run AI Enhance after each successful transcription. Off by default; manual is safer for quota and privacy.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.aiAutoRun, onCheckedChange = viewModel::setAiAutoRun)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send short clips to Gemini", fontWeight = FontWeight.SemiBold)
                            Text("When on, AI Enhance can re-listen to short clips only. When off, it uses text repair only.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.aiAudioRelisten, onCheckedChange = viewModel::setAiAudioRelisten)
                    }
                }
                item {
                    Column {
                        Text("Silence gap: ${settings.aiGapMinutes} minutes", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = settings.aiGapMinutes.toFloat(),
                            onValueChange = { viewModel.setAiGapMinutes(it.toInt()) },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                    }
                }
            }
            item { HorizontalDivider() }
            item { Text("API key usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { UsageCard(settings.provider, usage, viewModel::refreshUsage) }
            item { HorizontalDivider() }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow mobile data", fontWeight = FontWeight.SemiBold)
                        Text("Off keeps uploads on unmetered Wi‑Fi.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.allowMobileData, onCheckedChange = viewModel::setMobileData)
                }
            }
            item {
                OutlinedTextField(
                    value = keyterms,
                    onValueChange = { keyterms = it },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    label = { Text("Names and jargon") },
                    supportingText = { Text("One per line or comma-separated; sent only with transcription requests.") },
                )
            }
            item { Button(onClick = { viewModel.setKeyterms(keyterms) }) { Text("Save keyterms") } }
            item { Text("API keys are encrypted with Android Keystore and excluded from device backups. Audio is sent only to the provider you select.", style = MaterialTheme.typography.bodySmall) }
            item {
                Text(
                    "Debrief v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun AudioQualitySelector(
    selected: TranscriptionAudioQuality,
    onSelected: (TranscriptionAudioQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = selected == TranscriptionAudioQuality.ORIGINAL,
            onClick = { onSelected(TranscriptionAudioQuality.ORIGINAL) },
            label = { Text("Original · best accuracy") },
        )
        FilterChip(
            selected = selected == TranscriptionAudioQuality.BALANCED,
            onClick = { onSelected(TranscriptionAudioQuality.BALANCED) },
            label = { Text("Balanced · 96 kbps mono") },
        )
        FilterChip(
            selected = selected == TranscriptionAudioQuality.DATA_SAVER,
            onClick = { onSelected(TranscriptionAudioQuality.DATA_SAVER) },
            label = { Text("Data saver · 64 kbps mono") },
        )
        Text(
            when (selected) {
                TranscriptionAudioQuality.ORIGINAL -> "Streams the linked recording unchanged. Uses more upload data and preserves its channels and codec."
                TranscriptionAudioQuality.BALANCED -> "Creates a temporary 96 kbps mono AAC upload copy. The original remains untouched."
                TranscriptionAudioQuality.DATA_SAVER -> "Creates the smallest temporary upload copy. Best for limited bandwidth, not difficult audio."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageCard(provider: String, state: UsageUiState, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val providerLabel = if (provider == "assemblyai") "AssemblyAI" else "Deepgram"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(providerLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Refresh API usage")
                }
            }
            state.snapshot?.let { usage ->
                Text("Key ID ${usage.keyId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Tracked on this device: ${usage.localRequests} successful ${if (usage.localRequests == 1L) "recording" else "recordings"} · ${formatUsageDuration(usage.localAudioMs)} audio",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (usage.providerRequests != null || usage.providerAudioHours != null) {
                    Text(
                        "Provider this month: ${usage.providerRequests ?: 0} requests · ${"%.2f".format(usage.providerAudioHours ?: 0.0)} audio hours",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                usage.providerSpendUsd?.let { Text("Spend this month: \$${"%.2f".format(it)}") }
                usage.balanceUsd?.let { Text("Available balance: \$${"%.2f".format(it)}") }
                usage.providerMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (provider == "assemblyai") {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.assemblyai.com/dashboard/account/billing")))
                }) { Text("Open AssemblyAI usage dashboard") }
            }
        }
    }
}

private fun formatUsageDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) "${hours}h ${remainingMinutes}m" else "${minutes}m"
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.weight(1f), label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onSave() }),
        )
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onSave, enabled = value.isNotBlank()) { Text("Save") }
    }
}

@Composable
fun ReviewScreen(viewModel: ReviewViewModel, initialTimestamp: Long, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val reloadVersion by viewModel.reloadVersion.collectAsStateWithLifecycle()
    val recording = state.recording
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var position by remember { mutableLongStateOf(initialTimestamp) }
    var duration by remember { mutableLongStateOf(1) }
    var playing by remember { mutableStateOf(false) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var skipIntervalMs by rememberSaveable { mutableLongStateOf(DEFAULT_PLAYBACK_SKIP_MS) }
    var follow by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var localResults by remember { mutableStateOf(emptyList<SearchHit>()) }
    var addComment by remember { mutableStateOf(false) }
    var showSetControls by rememberSaveable { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<CommentEntity?>(null) }
    var editingSet by remember { mutableStateOf<ConversationSetEntity?>(null) }
    var deletingSet by remember { mutableStateOf<ConversationSetEntity?>(null) }
    var renamingSpeaker by remember { mutableStateOf<String?>(null) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var cleanedView by rememberSaveable { mutableStateOf(false) }
    var selectionStart by remember { mutableStateOf<Long?>(null) }
    var selectionEnd by remember { mutableStateOf<Long?>(null) }
    var reviewingRepair by remember { mutableStateOf<RepairEntity?>(null) }
    var showingQualityDetails by remember { mutableStateOf(false) }
    var qualityDismissed by remember(state.qualityReport?.createdAt) { mutableStateOf(false) }
    val organizeRunning = state.ai?.status == AiPassStatus.RUNNING
    val showAiEnhance = appSettings.aiEnhanceEnabled
    val enhanceRunning = showAiEnhance && (state.repairRun?.status == RepairRunStatus.RUNNING || state.repairRun?.status == RepairRunStatus.QUEUED)
    val unresolvedSuspects = state.suspectSpans.count { !it.resolved }
    val activeRepairs = if (showAiEnhance) state.repairs.filter { it.applied && !it.reverted } else emptyList()
    val openManualSet = state.sets.lastOrNull { it.isOpenManualSet() }

    LaunchedEffect(state.repairRun?.status, state.repairs.size) {
        if (state.repairRun?.status == RepairRunStatus.READY && activeRepairs.isNotEmpty()) cleanedView = true
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    DisposableEffect(recording?.documentUri) {
        if (recording != null) {
            runCatching {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(recording.documentUri)))
                    setPlaybackSpeed(playbackSpeed)
                    prepare()
                    seekTo(if (initialTimestamp > 0) initialTimestamp else recording.playbackPositionMs)
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    })
                }
            }.onSuccess { player = it }
                .onFailure {
                    scope.launch { snackbar.showSnackbar("Couldn't open this audio file. Re-link the folder and try again.") }
                }
        }
        onDispose {
            player?.let {
                viewModel.savePlaybackPosition(it.currentPosition)
                runCatching { it.release() }
            }
            player = null
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            player?.let { position = it.currentPosition.coerceAtLeast(0); if (it.duration > 0) duration = it.duration }
            delay(300)
        }
    }
    val activeIndex = state.segments.indexOfLast { position >= it.startMs }.coerceAtLeast(0)
    LaunchedEffect(activeIndex, follow, state.segments.size, reloadVersion) {
        if (follow && state.segments.isNotEmpty()) {
            listState.scrollToItem(activeIndex.coerceIn(state.segments.indices))
        }
    }
    LaunchedEffect(query) {
        delay(250)
        localResults = if (query.isBlank()) emptyList() else viewModel.search(query)
    }
    fun seekBy(deltaMs: Long) {
        val target = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(1L))
        position = target
        player?.seekTo(target)
        follow = true
    }
    fun cycleSkipInterval() {
        skipIntervalMs = nextPlaybackSkipInterval(skipIntervalMs)
        scope.launch { snackbar.showSnackbar("Skip interval: ${formatPlaybackSkipInterval(skipIntervalMs)}") }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 360.dp)) {
                ChaptersDrawerContent(
                    recording = recording,
                    ai = state.ai,
                    sets = state.sets,
                    comments = state.comments,
                    suggestions = state.suggestions,
                    aliases = state.aliases,
                    positionMs = position,
                    onClose = { scope.launch { drawerState.close() } },
                    onSkip = viewModel::setSkipAiPass,
                    onUndoRename = viewModel::undoRename,
                    onConfirmSuggestion = viewModel::confirmSuggestion,
                    onSeek = { timestamp ->
                        player?.seekTo(timestamp)
                        position = timestamp
                        follow = true
                        scope.launch { drawerState.close() }
                    },
                    onEditSet = { editingSet = it },
                    onDeleteSet = { deletingSet = it },
                    onMerge = viewModel::mergeWithNext,
                    onSplit = viewModel::splitSet,
                )
            }
        },
    ) {
      Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(recording?.displayName ?: "Recording", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) { Icon(Icons.Default.MoreVert, "Recording actions") }
                        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Export Markdown") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    overflowExpanded = false
                                    scope.launch {
                                        runCatching {
                                            val markdown = viewModel.exportMarkdown()
                                            require(markdown.isNotBlank()) { "No transcript is available to export" }
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                type = "text/markdown"; putExtra(Intent.EXTRA_TEXT, markdown); putExtra(Intent.EXTRA_SUBJECT, recording?.displayName)
                                            }, "Export transcript"))
                                        }.onFailure { snackbar.showSnackbar("Couldn't export the transcript.") }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (organizeRunning) "Organizing…" else "Organize Recording") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                enabled = !organizeRunning,
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.runAiPass()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Slider(
                    value = position.coerceAtMost(duration).toFloat(),
                    onValueChange = { value -> position = value.toLong(); player?.seekTo(position) },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                )
                if (showAiEnhance) {
                    ScrubberHeatTicks(
                        suspects = state.suspectSpans,
                        repairs = activeRepairs,
                        durationMs = duration,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimestamp(position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    PlaybackSpeedControl(
                        speed = playbackSpeed,
                        onSpeedSelected = { speed ->
                            playbackSpeed = speed
                            runCatching { player?.setPlaybackSpeed(speed) }
                                .onFailure { scope.launch { snackbar.showSnackbar("Couldn't change playback speed.") } }
                        },
                    )
                    PlaybackSkipButton(
                        forward = false,
                        intervalMs = skipIntervalMs,
                        onClick = { seekBy(-skipIntervalMs) },
                        onLongClick = { cycleSkipInterval() },
                    )
                    IconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
                    }
                    PlaybackSkipButton(
                        forward = true,
                        intervalMs = skipIntervalMs,
                        onClick = { seekBy(skipIntervalMs) },
                        onLongClick = { cycleSkipInterval() },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(formatTimestamp(duration), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Search this recording") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                )
                ReviewToolbarActions(
                    showEnhance = showAiEnhance,
                    enhanceRunning = enhanceRunning,
                    suspectCount = unresolvedSuspects,
                    onReload = { follow = true; viewModel.reloadTranscript() },
                    onRunEnhance = viewModel::runAiEnhance,
                    onAddComment = { addComment = true },
                    onCommentLongPress = { showSetControls = !showSetControls },
                    onOpenChapters = { scope.launch { drawerState.open() } },
                )
            }
            if (showSetControls) {
                ManualSetControlsPanel(
                    positionMs = position,
                    openSetTitle = openManualSet?.title,
                    onStartSet = {
                        viewModel.markSetStart(position)
                        showSetControls = false
                    },
                    onEndSet = {
                        viewModel.markSetEnd(position)
                        showSetControls = false
                    },
                )
            }
            state.qualityReport?.takeIf { !qualityDismissed }?.let { report ->
                TranscriptQualityCard(
                    report = report,
                    onDetails = { showingQualityDetails = true },
                    onDismiss = { qualityDismissed = true },
                )
            }
            val repairRun = state.repairRun
            if (showAiEnhance && repairRun != null) {
                EnhanceProgressBanner(
                    run = repairRun,
                    repairs = state.repairs,
                    onResume = viewModel::runAiEnhance,
                )
            } else if (showAiEnhance && unresolvedSuspects > 0 && query.isBlank()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))) {
                    Text("AI Enhance found $unresolvedSuspects rough spots. Tap the sparkle button to repair them.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showAiEnhance) {
                SelectionPill(
                    startMs = selectionStart,
                    endMs = selectionEnd,
                    onClear = { selectionStart = null; selectionEnd = null },
                    onEnhance = {
                        val start = selectionStart
                        val end = selectionEnd
                        if (start != null && end != null) {
                            viewModel.runEnhanceSelection(start, end)
                            selectionStart = null
                            selectionEnd = null
                        }
                    },
                )
            }
            if (showAiEnhance && state.repairs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cleaned view", fontWeight = FontWeight.SemiBold)
                        Text("Raw transcript remains unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(cleanedView, onCheckedChange = { cleanedView = it })
                }
            }
            if (query.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(localResults) { hit -> SearchHitCard(hit) { player?.seekTo(hit.timestampMs); position = hit.timestampMs; query = ""; follow = true } }
                }
            } else if (recording?.status != RecordingStatus.READY) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (recording?.status == RecordingStatus.TRANSCRIBING) "Transcription in progress…" else "Transcribe this recording to see its synced transcript.")
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val leadingComments = leadingComments(state.comments, state.segments)
                    if (leadingComments.isNotEmpty()) {
                        item {
                            StandaloneCommentsCard(
                                comments = leadingComments,
                                onSeek = { timestamp -> player?.seekTo(timestamp); position = timestamp; follow = true },
                                onComment = { editingComment = it },
                                onDeleteComment = viewModel::deleteComment,
                            )
                        }
                    }
                    items(state.segments.size) { index ->
                        val segment = state.segments[index]
                        val comments = commentsForSegment(state.comments, state.segments, index, duration)
                        val segmentRepairs = state.repairs.overlappingRepairs(segment.startMs, segment.endMs)
                        val segmentSuspects = state.suspectSpans.overlappingSuspects(segment.startMs, segment.endMs).filterNot { it.resolved }
                        val segmentSet = state.sets.lastOrNull { it.overlapsRange(segment.startMs, segment.endMs, duration) }
                        SegmentCard(
                            speaker = state.aliases[segment.speakerId] ?: segment.speakerId,
                            timestamp = segment.startMs,
                            text = if (cleanedView) cleanedText(segment, segmentRepairs) else segment.text,
                            active = index == activeIndex,
                            suspect = showAiEnhance && segmentSuspects.isNotEmpty(),
                            repaired = showAiEnhance && segmentRepairs.any { it.applied && !it.reverted },
                            setLabel = segmentSet?.title,
                            setColorIndex = segmentSet?.orderIndex,
                            comments = comments,
                            onSeek = { player?.seekTo(segment.startMs); position = segment.startMs; follow = true },
                            onLongPress = {
                                if (showAiEnhance) {
                                    if (selectionStart == null || selectionEnd != null) {
                                        selectionStart = segment.startMs
                                        selectionEnd = null
                                    } else {
                                        selectionEnd = segment.endMs
                                    }
                                }
                            },
                            onRename = { renamingSpeaker = segment.speakerId },
                            onReviewRepair = { segmentRepairs.firstOrNull()?.let { reviewingRepair = it } },
                            onComment = { comment -> editingComment = comment },
                            onDeleteComment = viewModel::deleteComment,
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
    }

    if (addComment) TextEntryDialog("Comment at ${formatTimestamp(position)}", "Add", onDismiss = { addComment = false }) {
        viewModel.addComment(position, it); addComment = false
    }
    editingComment?.let { comment ->
        TextEntryDialog("Edit comment", "Save", initial = comment.text, onDismiss = { editingComment = null }) {
            viewModel.editComment(comment, it); editingComment = null
        }
    }
    editingSet?.let { set ->
        SetEditDialog(
            set = set,
            onDismiss = { editingSet = null },
            onConfirm = { title, startMs, endMs ->
                viewModel.editSet(set.id, title, startMs, endMs)
                editingSet = null
            },
        )
    }
    deletingSet?.let { set ->
        AlertDialog(
            onDismissRequest = { deletingSet = null },
            title = { Text("Delete ${set.title}?") },
            text = { Text("This removes the set marker only. Transcript text, comments, and audio are not deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSet(set.id)
                        deletingSet = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingSet = null }) { Text("Cancel") } },
        )
    }
    renamingSpeaker?.let { id ->
        TextEntryDialog("Rename $id", "Apply", initial = state.aliases[id].orEmpty(), onDismiss = { renamingSpeaker = null }) {
            viewModel.renameSpeaker(id, it); renamingSpeaker = null
        }
    }
    reviewingRepair?.let { repair ->
        RepairReviewDialog(
            repair = repair,
            onDismiss = { reviewingRepair = null },
            onAccept = {
                viewModel.acceptRepair(repair.id)
                reviewingRepair = null
            },
            onRevert = {
                viewModel.revertRepair(repair.id)
                reviewingRepair = null
            },
        )
    }
    val qualityReportForDialog = state.qualityReport
    if (showingQualityDetails && qualityReportForDialog != null) {
        TranscriptQualityDetailsDialog(
            report = qualityReportForDialog,
            onDismiss = { showingQualityDetails = false },
        )
    }
}

internal val PLAYBACK_SPEED_OPTIONS = listOf(1f, 1.2f, 1.5f, 2f, 3f, 4f)
internal val PLAYBACK_SKIP_INTERVALS_MS = listOf(3_000L, 1_000L, 5_000L)
internal const val DEFAULT_PLAYBACK_SKIP_MS = 3_000L

internal fun formatPlaybackSpeed(speed: Float): String = when (speed) {
    1f -> "1×"
    1.2f -> "1.2×"
    1.5f -> "1.5×"
    2f -> "2×"
    3f -> "3×"
    4f -> "4×"
    else -> "${speed}×"
}

internal fun nextPlaybackSkipInterval(currentMs: Long): Long {
    val index = PLAYBACK_SKIP_INTERVALS_MS.indexOf(currentMs)
    return PLAYBACK_SKIP_INTERVALS_MS[(index + 1).floorMod(PLAYBACK_SKIP_INTERVALS_MS.size)]
}

internal fun formatPlaybackSkipInterval(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(1)
    return "$seconds " + if (seconds == 1L) "second" else "seconds"
}

private fun skipIntervalNumber(milliseconds: Long): String = ((milliseconds / 1_000).coerceAtLeast(1)).toString()

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackSkipButton(
    forward: Boolean,
    intervalMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val label = formatPlaybackSkipInterval(intervalMs)
    val direction = if (forward) "forward" else "back"
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Skip $direction $label. Long press to change skip interval." },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) Icons.Default.Forward5 else Icons.Default.Replay5,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
        Text(
            skipIntervalNumber(intervalMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                .padding(horizontal = 2.dp),
        )
    }
}

@Composable
internal fun PlaybackSpeedControl(
    speed: Float,
    onSpeedSelected: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val speedLabel = formatPlaybackSpeed(speed)

    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Playback speed, $speedLabel" },
        ) {
            Text(speedLabel, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PLAYBACK_SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(option)) },
                    onClick = {
                        expanded = false
                        onSpeedSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ReviewToolbarActions(
    showEnhance: Boolean,
    enhanceRunning: Boolean,
    suspectCount: Int,
    onReload: () -> Unit,
    onRunEnhance: () -> Unit,
    onAddComment: () -> Unit,
    onCommentLongPress: () -> Unit,
    onOpenChapters: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, "Reload transcript") }
        if (showEnhance) {
            IconButton(
                onClick = onRunEnhance,
                enabled = !enhanceRunning,
                modifier = Modifier.semantics { contentDescription = "Run AI Enhance" },
            ) {
                if (enhanceRunning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Box {
                    Icon(Icons.Default.AutoAwesome, null)
                    if (suspectCount > 0) {
                        Text(
                            suspectCount.coerceAtMost(99).toString(),
                            modifier = Modifier.align(Alignment.TopEnd)
                                .background(Color(0xFFFFC107), RoundedCornerShape(99.dp))
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        CommentActionButton(onClick = onAddComment, onLongClick = onCommentLongPress)
        IconButton(onClick = onOpenChapters) { Icon(Icons.AutoMirrored.Filled.List, "Open chapters") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentActionButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Add comment" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.AddComment, null)
    }
}

@Composable
internal fun ManualSetControlsPanel(
    positionMs: Long,
    openSetTitle: String?,
    onStartSet: () -> Unit,
    onEndSet: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (openSetTitle == null) "Manual set marker at ${formatTimestamp(positionMs)}"
                else "$openSetTitle is open. Mark its end at ${formatTimestamp(positionMs)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = onStartSet,
                    enabled = openSetTitle == null,
                    modifier = Modifier.semantics { contentDescription = "Mark set start" },
                ) {
                    Icon(Icons.Default.Flag, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set start")
                }
                OutlinedButton(
                    onClick = onEndSet,
                    enabled = openSetTitle != null,
                    modifier = Modifier.semantics { contentDescription = "Mark set end" },
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set end")
                }
            }
        }
    }
}

internal fun leadingComments(
    comments: List<CommentEntity>,
    segments: List<com.andyluu.debrief.data.TranscriptSegmentEntity>,
): List<CommentEntity> = comments.filter { comment ->
    segments.firstOrNull()?.let { comment.timestampMs < it.startMs } ?: true
}

internal fun commentsForSegment(
    comments: List<CommentEntity>,
    segments: List<com.andyluu.debrief.data.TranscriptSegmentEntity>,
    index: Int,
    durationMs: Long,
): List<CommentEntity> {
    val segment = segments.getOrNull(index) ?: return emptyList()
    val windowEnd = segments.getOrNull(index + 1)?.startMs?.minus(1) ?: maxOf(segment.endMs, durationMs)
    return comments.filter { it.timestampMs in segment.startMs..windowEnd }
}

@Composable
private fun ScrubberHeatTicks(
    suspects: List<SuspectSpanEntity>,
    repairs: List<RepairEntity>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0) return
    Canvas(modifier) {
        suspects.filterNot { it.resolved }.forEach { span ->
            val x = size.width * (span.startMs / durationMs.toFloat()).coerceIn(0f, 1f)
            drawLine(
                color = Color(0xFFFFB300),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        repairs.forEach { repair ->
            val x = size.width * (repair.startMs / durationMs.toFloat()).coerceIn(0f, 1f)
            drawLine(
                color = Color(0xFF2E7D32),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun EnhanceProgressBanner(
    run: RepairRunEntity,
    repairs: List<RepairEntity>,
    onResume: () -> Unit,
) {
    val progress = if (run.totalSteps > 0) run.completedSteps / run.totalSteps.toFloat() else 0f
    val color = when (run.status) {
        RepairRunStatus.READY -> Color(0xFFD5F5DF)
        RepairRunStatus.PARTIAL, RepairRunStatus.FAILED -> Color(0xFFFFDAD6)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI Enhance", fontWeight = FontWeight.SemiBold)
                    Text(run.stageLabel, style = MaterialTheme.typography.bodySmall)
                }
                if (run.status == RepairRunStatus.PARTIAL || run.status == RepairRunStatus.FAILED) {
                    TextButton(onClick = onResume) { Text("Resume") }
                }
            }
            if (run.status == RepairRunStatus.RUNNING || run.status == RepairRunStatus.QUEUED) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            run.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (run.status == RepairRunStatus.READY && repairs.isNotEmpty()) {
                Text("${run.fixedCount} fixed · ${run.inaudibleCount} still inaudible · tap a green span to review.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SelectionPill(
    startMs: Long?,
    endMs: Long?,
    onClear: () -> Unit,
    onEnhance: () -> Unit,
) {
    if (startMs == null) return
    val range = if (endMs == null) {
        "Selection starts at ${formatTimestamp(startMs)}. Long-press another transcript card to set the end."
    } else {
        "${formatTimestamp(minOf(startMs, endMs))} to ${formatTimestamp(maxOf(startMs, endMs))}"
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(range, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text("Clear") }
            Button(onClick = onEnhance, enabled = endMs != null) { Text("Enhance Selection") }
        }
    }
}

private fun cleanedText(segment: TranscriptSegmentEntity, repairs: List<RepairEntity>): String {
    var text = segment.text
    val active = repairs.filter { it.applied && !it.reverted && it.repaired?.isNotBlank() == true }
    val appended = mutableListOf<String>()
    active.forEach { repair ->
        val replacement = repair.repaired.orEmpty()
        text = if (repair.original.isNotBlank() && text.contains(repair.original, ignoreCase = true)) {
            text.replace(repair.original, replacement, ignoreCase = true)
        } else {
            appended += replacement
            text
        }
    }
    return if (appended.isEmpty()) text else text + "\n\nCleaned: " + appended.distinct().joinToString(" / ")
}

private fun List<RepairEntity>.overlappingRepairs(startMs: Long, endMs: Long) =
    filter { it.startMs <= endMs && it.endMs >= startMs }

private fun List<SuspectSpanEntity>.overlappingSuspects(startMs: Long, endMs: Long) =
    filter { it.startMs <= endMs && it.endMs >= startMs }

@Composable
private fun StandaloneCommentsCard(
    comments: List<CommentEntity>,
    onSeek: (Long) -> Unit,
    onComment: (CommentEntity) -> Unit,
    onDeleteComment: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Comments", fontWeight = FontWeight.SemiBold)
            comments.forEach { comment ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSeek(comment.timestampMs) }.padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatTimestamp(comment.timestampMs) + "  " + comment.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onComment(comment) }, Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Edit comment", Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteComment(comment.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Delete comment", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AiPassPanel(
    recording: RecordingEntity?,
    ai: AiRecordingEntity?,
    sets: List<ConversationSetEntity>,
    suggestions: List<com.andyluu.debrief.data.SpeakerSuggestionEntity>,
    aliases: Map<String, String>,
    positionMs: Long,
    onSkip: (Boolean) -> Unit,
    onUndoRename: () -> Unit,
    onConfirmSuggestion: (com.andyluu.debrief.data.SpeakerSuggestionEntity) -> Unit,
    onSeek: (Long) -> Unit,
    onMerge: (String) -> Unit,
    onSplit: (String, Long) -> Unit,
) {
    val skipped = ai?.skipAiPass == true
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI pass", fontWeight = FontWeight.Bold)
                    Text(
                        when (ai?.status) {
                            AiPassStatus.RUNNING -> "Analyzing transcript…"
                            AiPassStatus.READY -> "Analysis ready"
                            AiPassStatus.FAILED -> "Needs attention"
                            AiPassStatus.SKIPPED -> "Skipped for this recording"
                            else -> "Not run"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = skipped, onCheckedChange = onSkip, modifier = Modifier.semantics { contentDescription = "Skip AI pass" })
            }
            if (ai?.originalDisplayName != null && recording?.displayName != ai.originalDisplayName) {
                OutlinedButton(onClick = onUndoRename) { Text("Undo rename") }
            }
            ai?.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ai?.summary?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onConfirmSuggestion(suggestion) },
                    label = { Text("Confirm ${aliases[suggestion.speakerId] ?: suggestion.speakerId} as ${suggestion.suggestedName}") },
                )
                if (suggestion.evidence.isNotBlank()) {
                    Text(suggestion.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (sets.isNotEmpty()) {
                HorizontalDivider()
                Text("Conversation sets", fontWeight = FontWeight.SemiBold)
                sets.forEachIndexed { index, set ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSeek(set.startMs) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatTimestamp(set.startMs)}  ${set.title}", fontWeight = FontWeight.SemiBold)
                            if (set.summary.isNotBlank()) Text(set.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (index < sets.lastIndex) TextButton(onClick = { onMerge(set.id) }) { Text("Merge next") }
                    }
                }
                val activeSet = sets.lastOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
                OutlinedButton(
                    onClick = { activeSet?.let { onSplit(it.id, positionMs) } },
                    enabled = activeSet != null && positionMs > activeSet.startMs + 1_000 && positionMs < activeSet.endMs - 1_000,
                ) { Text("Split current set here") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentCard(
    speaker: String,
    timestamp: Long,
    text: String,
    active: Boolean,
    suspect: Boolean,
    repaired: Boolean,
    setLabel: String?,
    setColorIndex: Int?,
    comments: List<CommentEntity>,
    onSeek: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onReviewRepair: () -> Unit,
    onComment: (CommentEntity) -> Unit,
    onDeleteComment: (String) -> Unit,
) {
    val baseColor = when {
        repaired -> Color(0xFFD5F5DF)
        suspect -> Color(0xFFFFF3CD)
        active -> MaterialTheme.colorScheme.primaryContainer
        setColorIndex != null -> manualSetContainerColor(setColorIndex)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).combinedClickable(onClick = onSeek, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = baseColor),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onRename, label = { Text(speaker) })
                Spacer(Modifier.width(8.dp))
                Text(formatTimestamp(timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (setLabel != null && setColorIndex != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        setLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(manualSetAccentColor(setColorIndex), RoundedCornerShape(99.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (repaired) TextButton(onClick = onReviewRepair) { Text("Review") }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            if (suspect && !repaired) {
                Text("Rough spot", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A5A00), modifier = Modifier.padding(top = 4.dp))
            }
            comments.forEach { comment ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${formatTimestamp(comment.timestampMs)}  ${comment.text}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onComment(comment) }, Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Edit comment", Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteComment(comment.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Delete comment", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RepairReviewDialog(
    repair: RepairEntity,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onRevert: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review AI repair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatTimestamp(repair.startMs)} · ${repair.source} · ${repair.confidence}", style = MaterialTheme.typography.labelMedium)
                Text("Original", fontWeight = FontWeight.SemiBold)
                Text(repair.original.ifBlank { "(empty)" })
                Text("Repaired", fontWeight = FontWeight.SemiBold)
                Text(repair.repaired ?: "[inaudible]")
                if (repair.reason.isNotBlank()) {
                    Text(repair.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                repair.clipUri?.let { ClipLoopPlayer(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = !repair.applied || repair.reverted) { Text("Accept") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRevert, enabled = repair.applied && !repair.reverted) { Text("Revert") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ClipLoopPlayer(clipUri: String) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(clipUri) {
        val created = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(clipUri)))
            repeatMode = Player.REPEAT_MODE_ONE
            setPlaybackSpeed(0.75f)
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            })
        }
        player = created
        onDispose {
            runCatching { created.release() }
            player = null
        }
    }
    OutlinedButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }) {
        Text(if (playing) "Pause clip" else "Loop clip at 0.75×")
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), minLines = 2) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetEditDialog(
    set: ConversationSetEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, Long, Long?) -> Unit,
) {
    var title by remember(set.id) { mutableStateOf(set.title) }
    var start by remember(set.id) { mutableStateOf(formatTimestamp(set.startMs)) }
    var end by remember(set.id) { mutableStateOf(if (set.isOpenManualSet()) "" else formatTimestamp(set.endMs)) }
    var error by remember(set.id) { mutableStateOf<String?>(null) }
    val parsedStart = parseTimestampInput(start)
    val parsedEnd = end.trim().takeIf(String::isNotBlank)?.let(::parseTimestampInput)
    val valid = title.isNotBlank() && parsedStart != null && (end.isBlank() || parsedEnd != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${set.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Set title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Start time") },
                    supportingText = { Text("Use m:ss or h:mm:ss") },
                    singleLine = true,
                    isError = start.isNotBlank() && parsedStart == null,
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("End time") },
                    supportingText = { Text("Leave blank to keep the set open") },
                    singleLine = true,
                    isError = end.isNotBlank() && parsedEnd == null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val startMs = parsedStart
                    val endMs = end.trim().takeIf(String::isNotBlank)?.let(::parseTimestampInput)
                    if (startMs == null || (end.isNotBlank() && endMs == null)) {
                        error = "Enter valid timestamps."
                    } else if (endMs != null && endMs <= startMs) {
                        error = "End time must be after start time."
                    } else {
                        onConfirm(title, startMs, endMs)
                    }
                },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) = IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
