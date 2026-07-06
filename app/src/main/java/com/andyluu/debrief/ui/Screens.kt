@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andyluu.debrief.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.SearchHit
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
    val conversationSets by viewModel.conversationSets.collectAsStateWithLifecycle()
    val aiByRecording = aiRecordings.associateBy { it.recordingId }
    val setsByRecording = conversationSets.groupBy { it.recordingId }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectableIds = recordings.filter { it.isTranscribable() }.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(selectableIds) { selectedIds = selectedIds.intersect(selectableIds) }
    val selectionMode = selectedIds.isNotEmpty()
    Scaffold(
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
                if (!viewModel.hasDeepgramKey() && settings.provider == "deepgram") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onOpenSettings),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) { Text("Add your Deepgram key in Settings before transcribing.", Modifier.padding(14.dp)) }
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
                                sets = setsByRecording[recording.id].orEmpty(),
                                selectionMode = selectionMode,
                                selected = recording.id in selectedIds,
                                onOpen = { onOpenRecording(recording.id, 0) },
                                onOpenAt = { onOpenRecording(recording.id, it) },
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
    sets: List<ConversationSetEntity> = emptyList(),
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onOpenAt: (Long) -> Unit = { onOpen() },
    onToggleSelection: () -> Unit,
) {
    val selectable = recording.isTranscribable()
    var expanded by remember(recording.id) { mutableStateOf(false) }
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
            if (recording.status == RecordingStatus.TRANSCRIBING || recording.status == RecordingStatus.QUEUED) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            recording.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ai?.summary?.takeIf(String::isNotBlank)?.let {
                Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (sets.isNotEmpty() && !selectionMode) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide ${sets.size} sets" else "Show ${sets.size} sets")
                }
                if (expanded) {
                    sets.forEach { set ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenAt(set.startMs) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(formatTimestamp(set.startMs), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(set.title, fontWeight = FontWeight.SemiBold)
                                if (set.summary.isNotBlank()) Text(set.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
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
    LaunchedEffect(query) {
        delay(250)
        results = if (query.isBlank()) emptyList() else viewModel.search(query)
    }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Search everything") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(settings.provider) { viewModel.refreshUsage(settings.provider) }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Transcription provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.provider == "deepgram", onClick = { viewModel.setProvider("deepgram") }, label = { Text("Deepgram Nova-3") })
                    FilterChip(selected = settings.provider == "assemblyai", onClick = { viewModel.setProvider("assemblyai") }, label = { Text("AssemblyAI") })
                }
            }
            item {
                SecretField(
                    label = if (viewModel.hasDeepgramKey()) "Deepgram key saved securely" else "Deepgram API key",
                    value = deepgram,
                    onChange = { deepgram = it },
                    onSave = {
                        if (deepgram.isNotBlank()) { viewModel.saveDeepgramKey(deepgram); deepgram = ""; scope.launch { snackbar.showSnackbar("Deepgram key encrypted and saved") } }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasAssemblyAiKey()) "AssemblyAI key saved securely" else "AssemblyAI API key",
                    value = assembly,
                    onChange = { assembly = it },
                    onSave = {
                        if (assembly.isNotBlank()) { viewModel.saveAssemblyAiKey(assembly); assembly = ""; scope.launch { snackbar.showSnackbar("AssemblyAI key encrypted and saved") } }
                    },
                )
            }
            item { HorizontalDivider() }
            item { Text("AI pass", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Text(
                    "Runs after transcription to detect conversation sets, identify speakers, summarize, and rename. Only transcript text is sent; audio is never sent to the AI provider.",
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
                        if (gemini.isNotBlank()) { viewModel.saveGeminiKey(gemini); gemini = ""; scope.launch { snackbar.showSnackbar("Gemini key encrypted and saved") } }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasOpenAiKey()) "Compatible key saved securely" else "OpenAI-compatible API key",
                    value = openAiKey,
                    onChange = { openAiKey = it },
                    onSave = {
                        if (openAiKey.isNotBlank()) { viewModel.saveOpenAiKey(openAiKey); openAiKey = ""; scope.launch { snackbar.showSnackbar("Compatible provider key encrypted and saved") } }
                    },
                )
            }
            item {
                SecretField(
                    label = if (viewModel.hasAnthropicKey()) "Anthropic key saved securely" else "Anthropic API key",
                    value = anthropicKey,
                    onChange = { anthropicKey = it },
                    onSave = {
                        if (anthropicKey.isNotBlank()) { viewModel.saveAnthropicKey(anthropicKey); anthropicKey = ""; scope.launch { snackbar.showSnackbar("Anthropic key encrypted and saved") } }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run automatically", fontWeight = FontWeight.SemiBold)
                        Text("Run the AI pass after each successful transcription.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.aiAutoRun, onCheckedChange = viewModel::setAiAutoRun)
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
            item {
                Button(onClick = {
                    viewModel.saveAiEndpoint(openAiBaseUrl, openAiModel, anthropicModel)
                    scope.launch { snackbar.showSnackbar("AI provider settings saved") }
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
            item { Button(onClick = { viewModel.setKeyterms(keyterms); scope.launch { snackbar.showSnackbar("Keyterms saved") } }) { Text("Save keyterms") } }
            item { Text("API keys are encrypted with Android Keystore and excluded from device backups. Audio is sent only to the provider you select.", style = MaterialTheme.typography.bodySmall) }
            item { Spacer(Modifier.height(24.dp)) }
        }
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
    val reloadVersion by viewModel.reloadVersion.collectAsStateWithLifecycle()
    val recording = state.recording
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var position by remember { mutableLongStateOf(initialTimestamp) }
    var duration by remember { mutableLongStateOf(1) }
    var playing by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var localResults by remember { mutableStateOf(emptyList<SearchHit>()) }
    var addComment by remember { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<CommentEntity?>(null) }
    var renamingSpeaker by remember { mutableStateOf<String?>(null) }

    DisposableEffect(recording?.documentUri) {
        if (recording != null) {
            val exo = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(recording.documentUri)))
                prepare()
                seekTo(if (initialTimestamp > 0) initialTimestamp else recording.playbackPositionMs)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                })
            }
            player = exo
        }
        onDispose {
            player?.let { viewModel.savePlaybackPosition(it.currentPosition); it.release() }
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(recording?.displayName ?: "Recording", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val markdown = viewModel.exportMarkdown()
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"; putExtra(Intent.EXTRA_TEXT, markdown); putExtra(Intent.EXTRA_SUBJECT, recording?.displayName)
                            }, "Export transcript"))
                        }
                    }) { Icon(Icons.Default.Share, "Export Markdown") }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimestamp(position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
                    }
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
                IconButton(onClick = { follow = true; viewModel.reloadTranscript() }) {
                    Icon(Icons.Default.Refresh, "Reload transcript")
                }
                IconButton(onClick = { addComment = true }) { Icon(Icons.Default.AddComment, "Add comment") }
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
                    item {
                        AiPassPanel(
                            recording = recording,
                            ai = state.ai,
                            sets = state.sets,
                            suggestions = state.suggestions,
                            aliases = state.aliases,
                            positionMs = position,
                            onRun = viewModel::runAiPass,
                            onSkip = viewModel::setSkipAiPass,
                            onUndoRename = viewModel::undoRename,
                            onConfirmSuggestion = viewModel::confirmSuggestion,
                            onSeek = { timestamp -> player?.seekTo(timestamp); position = timestamp; follow = true },
                            onMerge = viewModel::mergeWithNext,
                            onSplit = viewModel::splitSet,
                        )
                    }
                    items(state.segments.size) { index ->
                        val segment = state.segments[index]
                        val comments = state.comments.filter { it.timestampMs in segment.startMs..segment.endMs }
                        SegmentCard(
                            speaker = state.aliases[segment.speakerId] ?: segment.speakerId,
                            timestamp = segment.startMs,
                            text = segment.text,
                            active = index == activeIndex,
                            comments = comments,
                            onSeek = { player?.seekTo(segment.startMs); position = segment.startMs; follow = true },
                            onRename = { renamingSpeaker = segment.speakerId },
                            onComment = { comment -> editingComment = comment },
                            onDeleteComment = viewModel::deleteComment,
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
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
    renamingSpeaker?.let { id ->
        TextEntryDialog("Rename $id", "Apply", initial = state.aliases[id].orEmpty(), onDismiss = { renamingSpeaker = null }) {
            viewModel.renameSpeaker(id, it); renamingSpeaker = null
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
    onRun: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onUndoRename: () -> Unit,
    onConfirmSuggestion: (com.andyluu.debrief.data.SpeakerSuggestionEntity) -> Unit,
    onSeek: (Long) -> Unit,
    onMerge: (String) -> Unit,
    onSplit: (String, Long) -> Unit,
) {
    val running = ai?.status == AiPassStatus.RUNNING
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = !running && !skipped) {
                    Text(if (ai?.lastRunAt == null) "Run AI pass" else "Re-run AI pass")
                }
                if (ai?.originalDisplayName != null && recording?.displayName != ai.originalDisplayName) {
                    OutlinedButton(onClick = onUndoRename) { Text("Undo rename") }
                }
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

@Composable
private fun SegmentCard(
    speaker: String,
    timestamp: Long,
    text: String,
    active: Boolean,
    comments: List<CommentEntity>,
    onSeek: () -> Unit,
    onRename: () -> Unit,
    onComment: (CommentEntity) -> Unit,
    onDeleteComment: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onSeek),
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onRename, label = { Text(speaker) })
                Spacer(Modifier.width(8.dp))
                Text(formatTimestamp(timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
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
private fun BackButton(onBack: () -> Unit) = IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
