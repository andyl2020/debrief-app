package com.andyluu.debrief.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.data.AppSettings
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.RepairEntity
import com.andyluu.debrief.data.RepairRunEntity
import com.andyluu.debrief.data.SearchHit
import com.andyluu.debrief.data.SpeakerAliasEntity
import com.andyluu.debrief.data.SpeakerSuggestionEntity
import com.andyluu.debrief.data.SuspectSpanEntity
import com.andyluu.debrief.data.LocalApiUsage
import com.andyluu.debrief.data.TranscriptQualityReportEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptionAudioQuality
import com.andyluu.debrief.transcription.TranscriptionWorker
import com.andyluu.debrief.transcription.ApiUsageSnapshot
import com.andyluu.debrief.ai.AiPassWorker
import com.andyluu.debrief.enhance.AiEnhanceWorker
import com.andyluu.debrief.enhance.EnhanceConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class ReviewState(
    val recording: RecordingEntity? = null,
    val segments: List<TranscriptSegmentEntity> = emptyList(),
    val comments: List<CommentEntity> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val ai: AiRecordingEntity? = null,
    val sets: List<ConversationSetEntity> = emptyList(),
    val suggestions: List<SpeakerSuggestionEntity> = emptyList(),
    val suspectSpans: List<SuspectSpanEntity> = emptyList(),
    val repairRun: RepairRunEntity? = null,
    val repairs: List<RepairEntity> = emptyList(),
    val qualityReport: TranscriptQualityReportEntity? = null,
)

data class UsageUiState(
    val loading: Boolean = false,
    val snapshot: ApiUsageSnapshot? = null,
    val error: String? = null,
)

internal fun userMessage(fallback: String, error: Throwable): String = when (error) {
    is SecurityException -> "Permission was denied. Re-link the recordings folder and try again."
    is IOException -> fallback + " Check storage and your connection, then try again."
    is IllegalArgumentException -> error.message?.takeIf(String::isNotBlank)?.take(180) ?: fallback
    else -> fallback
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DebriefApplication
    private val services = app.services
    private val dao = services.database.dao()

    val recordings: StateFlow<List<RecordingEntity>> = dao.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val aiRecordings: StateFlow<List<AiRecordingEntity>> = dao.observeAllAiRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversationSets: StateFlow<List<ConversationSetEntity>> = dao.observeAllConversationSets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val repairRuns: StateFlow<List<RepairRunEntity>> = dao.observeAllRepairRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val qualityReports: StateFlow<List<TranscriptQualityReportEntity>> = dao.observeQualityReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _usage = MutableStateFlow(UsageUiState())
    val usage = _usage.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            services.settings.settings.map { it.folderUri }.filterNotNull().distinctUntilChanged().collect { uri ->
                runCatching { services.folders.scan(Uri.parse(uri)) }
                    .onFailure {
                        Log.e("DebriefScan", "Folder refresh failed", it)
                        _messages.emit(userMessage("Couldn't refresh the recordings folder.", it))
                    }
            }
        }
        viewModelScope.launch {
            recoverQueuedTranscriptions()
        }
    }

    fun linkFolder(uri: Uri, onComplete: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                services.settings.setFolderUri(uri.toString())
                services.folders.scan(uri)
            }
            result.exceptionOrNull()?.let { Log.e("DebriefScan", "Folder link failed", it) }
            result.exceptionOrNull()?.let { _messages.emit(userMessage("Couldn't open that folder.", it)) }
            onComplete(result)
        }
    }

    fun scan() {
        val uri = settings.value.folderUri ?: return
        viewModelScope.launch {
            runCatching { services.folders.scan(Uri.parse(uri)) }
                .onFailure {
                    Log.e("DebriefScan", "Folder refresh failed", it)
                    _messages.emit(userMessage("Couldn't refresh the recordings folder.", it))
                }
        }
    }

    fun transcribe(recordingIds: Collection<String>) {
        launchHandled("Couldn't queue transcription.") {
            val eligible = recordingIds.distinct().mapNotNull { dao.getRecording(it) }
                .filter { it.status == RecordingStatus.NEW || it.status == RecordingStatus.FAILED }
            val provider = settings.value.provider
            if (services.secrets.get(provider).isNullOrBlank()) {
                _messages.emit("Add your " + providerName(provider) + " API key in Settings before transcribing.")
                return@launchHandled
            }
            if (eligible.isEmpty()) {
                _messages.emit("Select at least one new or failed recording.")
                return@launchHandled
            }
            eligible.forEach { recording ->
                try {
                    dao.updateStatus(recording.id, RecordingStatus.QUEUED)
                    TranscriptionWorker.enqueue(app, recording.id, settings.value.allowMobileData, replaceExisting = true)
                } catch (error: Throwable) {
                    dao.updateStatus(recording.id, RecordingStatus.FAILED, "Could not queue transcription")
                    throw error
                }
            }
            _messages.emit(eligible.size.toString() + " " + if (eligible.size == 1) "recording queued." else "recordings queued.")
        }
    }

    suspend fun search(query: String, recordingId: String? = null): List<SearchHit> =
        try {
            withContext(Dispatchers.IO) { services.search.search(query, recordingId) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("DebriefSearch", "Search failed", error)
            _messages.emit(userMessage("Search is temporarily unavailable.", error))
            emptyList()
        }

    fun saveDeepgramKey(value: String, onResult: (Boolean) -> Unit = {}) =
        saveSecret("deepgram", "Deepgram", value, onResult) { refreshUsage("deepgram") }
    fun saveAssemblyAiKey(value: String, onResult: (Boolean) -> Unit = {}) =
        saveSecret("assemblyai", "AssemblyAI", value, onResult) { refreshUsage("assemblyai") }
    fun saveGeminiKey(value: String, onResult: (Boolean) -> Unit = {}) =
        saveSecret("gemini", "Gemini", value, onResult)
    fun saveOpenAiKey(value: String, onResult: (Boolean) -> Unit = {}) =
        saveSecret("openai_compatible", "OpenAI-compatible", value, onResult)
    fun saveAnthropicKey(value: String, onResult: (Boolean) -> Unit = {}) =
        saveSecret("anthropic", "Anthropic", value, onResult)
    fun hasDeepgramKey() = services.secrets.has("deepgram")
    fun hasAssemblyAiKey() = services.secrets.has("assemblyai")
    fun hasGeminiKey() = services.secrets.has("gemini")
    fun hasOpenAiKey() = services.secrets.has("openai_compatible")
    fun hasAnthropicKey() = services.secrets.has("anthropic")
    fun aiUsage(provider: String): LocalApiUsage? {
        val secretName = when (provider) {
            "openai" -> "openai_compatible"
            "anthropic" -> "anthropic"
            else -> "gemini"
        }
        val key = services.secrets.get(secretName) ?: return null
        return services.usage.get("ai_" + provider, key)
    }
    fun setMobileData(value: Boolean) = launchHandled("Couldn't update mobile-data settings.") {
        services.settings.setAllowMobileData(value)
        recoverQueuedTranscriptions()
    }
    fun setKeyterms(value: String) = launchHandled("Couldn't save keyterms.") {
        services.settings.setKeyterms(value)
        _messages.emit("Keyterms saved.")
    }
    fun setProvider(value: String) = launchHandled("Couldn't change transcription provider.") {
        services.settings.setProvider(value)
        refreshUsage(value)
    }
    fun setTranscriptionAudioQuality(value: TranscriptionAudioQuality) = launchHandled("Couldn't change transcription audio quality.") {
        services.settings.setTranscriptionAudioQuality(value)
        _messages.emit("Transcription audio quality set to ${audioQualityLabel(value)}.")
    }
    fun setAiProvider(value: String) = launchHandled("Couldn't change AI provider.") { services.settings.setAiProvider(value) }
    fun setAiEnhanceEnabled(value: Boolean) = launchHandled("Couldn't update AI Enhance visibility.") {
        services.settings.setAiEnhanceEnabled(value)
        _messages.emit(if (value) "AI Enhance tools enabled." else "AI Enhance tools hidden.")
    }
    fun setAiAutoRun(value: Boolean) = launchHandled("Couldn't update automatic AI settings.") { services.settings.setAiAutoRun(value) }
    fun setAiAudioRelisten(value: Boolean) = launchHandled("Couldn't update audio re-listen settings.") { services.settings.setAiAudioRelisten(value) }
    fun setAiGapMinutes(value: Int) = launchHandled("Couldn't update the silence gap.") { services.settings.setAiGapMinutes(value) }
    fun saveAiEndpoint(baseUrl: String, model: String, anthropicModel: String) = launchHandled("Couldn't save AI provider settings.") {
        services.settings.setOpenAiBaseUrl(baseUrl)
        services.settings.setOpenAiModel(model)
        services.settings.setAnthropicModel(anthropicModel)
        _messages.emit("AI provider settings saved.")
    }

    fun refreshUsage(provider: String = settings.value.provider) {
        val key = services.secrets.get(provider)
        if (key == null) {
            _usage.value = UsageUiState(error = "Save a ${providerName(provider)} API key to view usage.")
            return
        }
        viewModelScope.launch {
            _usage.value = UsageUiState(loading = true, snapshot = _usage.value.snapshot)
            _usage.value = runCatching { services.usageRepository.load(provider, key) }
                .fold(
                    onSuccess = { UsageUiState(snapshot = it) },
                    onFailure = { UsageUiState(error = "Usage refresh failed. Check your connection and try again.") },
                )
        }
    }

    private fun providerName(provider: String) = if (provider == "assemblyai") "AssemblyAI" else "Deepgram"
    private fun audioQualityLabel(value: TranscriptionAudioQuality) = when (value) {
        TranscriptionAudioQuality.ORIGINAL -> "Original"
        TranscriptionAudioQuality.BALANCED -> "Balanced"
        TranscriptionAudioQuality.DATA_SAVER -> "Data saver"
    }

    private fun saveSecret(
        name: String,
        label: String,
        value: String,
        onResult: (Boolean) -> Unit,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                _messages.emit("Enter a " + label + " API key first.")
                onResult(false)
                return@launch
            }
            try {
                services.secrets.put(name, trimmed)
                _messages.emit(label + " API key encrypted and saved.")
                onResult(true)
                onSaved()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e("DebriefSecrets", "Saving " + label + " key failed", error)
                _messages.emit(userMessage("Couldn't save the " + label + " API key.", error))
                onResult(false)
            }
        }
    }

    private fun launchHandled(fallback: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("DebriefUi", fallback, error)
            _messages.emit(userMessage(fallback, error))
        }
    }

    private suspend fun recoverQueuedTranscriptions() {
        val settings = services.settings.settings.first()
        val queued = dao.getRecordingsByStatus(RecordingStatus.QUEUED)
        queued.forEach { recording ->
            TranscriptionWorker.enqueue(app, recording.id, settings.allowMobileData, replaceExisting = true)
        }
        if (queued.isNotEmpty()) {
            _messages.emit(
                queued.size.toString() + " queued " +
                    if (queued.size == 1) "recording was recovered." else "recordings were recovered."
            )
        }
    }
}

class ReviewViewModel(
    application: Application,
    private val recordingId: String,
) : AndroidViewModel(application) {
    private val app = application as DebriefApplication
    private val services = app.services
    private val dao = services.database.dao()
    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _reloadVersion = MutableStateFlow(0)
    val reloadVersion = _reloadVersion.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private data class CoreReviewState(
        val recording: RecordingEntity?,
        val segments: List<TranscriptSegmentEntity>,
        val comments: List<CommentEntity>,
        val aliases: Map<String, String>,
        val ai: AiRecordingEntity?,
    )

    private data class EnhanceReviewState(
        val suspectSpans: List<SuspectSpanEntity>,
        val repairRun: RepairRunEntity?,
        val repairs: List<RepairEntity>,
    )

    private val coreState = combine(
        dao.observeRecording(recordingId),
        dao.observeSegments(recordingId),
        dao.observeComments(recordingId),
        dao.observeAliases(recordingId),
        dao.observeAiRecording(recordingId),
    ) { recording, segments, comments, aliases, ai ->
        CoreReviewState(recording, segments, comments, aliases.associate { it.speakerId to it.displayName }, ai)
    }

    private val enhanceState = combine(
        dao.observeSuspectSpans(recordingId),
        dao.observeLatestRepairRun(recordingId),
        dao.observeRepairs(recordingId),
    ) { suspectSpans, repairRun, repairs ->
        EnhanceReviewState(suspectSpans, repairRun, repairs)
    }

    val state: StateFlow<ReviewState> = combine(
        coreState,
        dao.observeConversationSets(recordingId),
        dao.observeSpeakerSuggestions(recordingId),
        enhanceState,
        dao.observeQualityReport(recordingId),
    ) { core, sets, suggestions, enhance, qualityReport ->
        ReviewState(
            core.recording,
            core.segments,
            core.comments,
            core.aliases,
            core.ai,
            sets,
            suggestions,
            enhance.suspectSpans,
            enhance.repairRun,
            enhance.repairs,
            qualityReport,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun savePlaybackPosition(positionMs: Long) = launchHandled("Couldn't save the playback position.") {
        dao.updatePlaybackPosition(recordingId, positionMs)
    }

    fun reloadTranscript() = launchHandled("Couldn't reload the transcript.") {
        val recording = dao.getRecording(recordingId)
        if (recording != null && dao.getSegments(recordingId).isEmpty()) {
            services.settings.settings.first().folderUri?.let { folderUri ->
                DocumentFile.fromTreeUri(app, Uri.parse(folderUri))?.let { root ->
                    services.sidecars.restoreIfPresent(root, recording)
                }
            }
        }
        _reloadVersion.update { it + 1 }
        _messages.emit(if (dao.getSegments(recordingId).isEmpty()) "No saved transcript was found." else "Transcript reloaded.")
    }

    fun addComment(timestampMs: Long, text: String) = launchHandled("Couldn't add the comment.") {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            _messages.emit("Comment text can't be empty.")
            return@launchHandled
        }
        dao.upsertComment(
            CommentEntity(
                id = UUID.randomUUID().toString(),
                recordingId = recordingId,
                timestampMs = timestampMs,
                text = trimmed,
            )
        )
        refreshDerivedData()
        _messages.emit("Comment added.")
    }

    fun editComment(comment: CommentEntity, text: String) = launchHandled("Couldn't update the comment.") {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            _messages.emit("Comment text can't be empty.")
            return@launchHandled
        }
        dao.upsertComment(comment.copy(text = trimmed, updatedAt = System.currentTimeMillis()))
        refreshDerivedData()
        _messages.emit("Comment updated.")
    }

    fun deleteComment(commentId: String) = launchHandled("Couldn't delete the comment.") {
        dao.deleteComment(commentId)
        refreshDerivedData()
        _messages.emit("Comment deleted.")
    }

    fun markSetStart(timestampMs: Long) = launchHandled("Couldn't mark the set start.") {
        val sets = dao.getConversationSets(recordingId)
        val openSet = sets.lastOrNull { it.isOpenManualSet() }
        if (openSet != null) {
            _messages.emit("End ${openSet.title} before starting another set.")
            return@launchHandled
        }
        val start = timestampMs.coerceAtLeast(0L)
        val setNumber = nextManualSetNumber(sets)
        val set = ConversationSetEntity(
            id = UUID.randomUUID().toString(),
            recordingId = recordingId,
            orderIndex = sets.size,
            startMs = start,
            endMs = start,
            title = "Set $setNumber",
            summary = "Manual set",
        )
        dao.upsertConversationSet(set)
        refreshDerivedData()
        _messages.emit("Set $setNumber started at ${formatTimestamp(start)}.")
    }

    fun markSetEnd(timestampMs: Long) = launchHandled("Couldn't mark the set end.") {
        val sets = dao.getConversationSets(recordingId)
        val openIndex = sets.indexOfLast { it.isOpenManualSet() }
        if (openIndex < 0) {
            _messages.emit("Start a set before marking its end.")
            return@launchHandled
        }
        val openSet = sets[openIndex]
        val end = timestampMs.coerceAtLeast(openSet.startMs + 1_000L)
        val speakerIds = dao.getSegments(recordingId)
            .filter { it.endMs >= openSet.startMs && it.startMs <= end }
            .map { it.speakerId }
            .distinct()
            .joinToString("|")
        val updated = openSet.copy(endMs = end, summary = "", speakerIds = speakerIds)
        persistSets(sets.mapIndexed { index, set -> if (index == openIndex) updated else set })
        _messages.emit("${openSet.title} ended at ${formatTimestamp(end)}.")
    }

    fun editSet(setId: String, title: String, startMs: Long, endMs: Long?) = launchHandled("Couldn't update the set.") {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) {
            _messages.emit("Set title can't be empty.")
            return@launchHandled
        }
        val start = startMs.coerceAtLeast(0L)
        val end = endMs ?: start
        if (endMs != null && end <= start) {
            _messages.emit("Set end must be after set start.")
            return@launchHandled
        }
        val sets = dao.getConversationSets(recordingId)
        val index = sets.indexOfFirst { it.id == setId }
        if (index < 0) {
            _messages.emit("Set no longer exists.")
            return@launchHandled
        }
        val otherOpenSet = sets.any { it.id != setId && it.isOpenManualSet() }
        if (endMs == null && otherOpenSet) {
            _messages.emit("End the current open set before making another set open.")
            return@launchHandled
        }
        val speakerIds = if (endMs == null) {
            ""
        } else {
            dao.getSegments(recordingId)
                .filter { it.endMs >= start && it.startMs <= end }
                .map { it.speakerId }
                .distinct()
                .joinToString("|")
        }
        val updated = sets[index].copy(
            startMs = start,
            endMs = end,
            title = trimmedTitle,
            summary = if (endMs == null) "Manual set" else sets[index].summary,
            speakerIds = speakerIds,
        )
        persistSets(sets.mapIndexed { setIndex, set -> if (setIndex == index) updated else set }.sortedBy { it.startMs })
        _messages.emit("Set updated.")
    }

    fun deleteSet(setId: String) = launchHandled("Couldn't delete the set.") {
        val sets = dao.getConversationSets(recordingId)
        val target = sets.firstOrNull { it.id == setId }
        if (target == null) {
            _messages.emit("Set no longer exists.")
            return@launchHandled
        }
        persistSets(sets.filterNot { it.id == setId })
        _messages.emit("${target.title} deleted.")
    }

    fun renameSpeaker(speakerId: String, displayName: String) = launchHandled("Couldn't rename the speaker.") {
        val trimmed = displayName.trim()
        if (trimmed.isBlank()) {
            _messages.emit("Speaker name can't be empty.")
            return@launchHandled
        }
        dao.upsertAlias(SpeakerAliasEntity(recordingId, speakerId, trimmed))
        refreshDerivedData()
        _messages.emit("Speaker renamed.")
    }

    fun runAiPass() = launchHandled("Couldn't start the AI pass.") {
        val settings = services.settings.settings.first()
        val current = dao.getAiRecording(recordingId) ?: AiRecordingEntity(recordingId)
        if (current.skipAiPass) {
            _messages.emit("Turn off Skip AI Pass for this recording first.")
            return@launchHandled
        }
        val recording = dao.getRecording(recordingId)
        if (recording?.status != RecordingStatus.READY || dao.getSegments(recordingId).isEmpty()) {
            _messages.emit("Finish transcription before running the AI pass.")
            return@launchHandled
        }
        val configurationError = aiConfigurationError(settings)
        if (configurationError != null) {
            dao.upsertAiRecording(current.copy(status = AiPassStatus.FAILED, errorMessage = configurationError))
            _messages.emit(configurationError)
            return@launchHandled
        }
        dao.upsertAiRecording(current.copy(status = AiPassStatus.RUNNING, errorMessage = null))
        AiPassWorker.enqueue(app, recordingId, allowMobileData = settings.allowMobileData, force = true)
        _messages.emit("AI pass queued.")
    }

    fun runAiEnhance() = launchHandled("Couldn't start AI Enhance.") {
        val settings = services.settings.settings.first()
        val current = dao.getAiRecording(recordingId) ?: AiRecordingEntity(recordingId)
        if (current.skipAiPass) {
            _messages.emit("Turn off Skip AI for this recording first.")
            return@launchHandled
        }
        val recording = dao.getRecording(recordingId)
        if (recording?.status != RecordingStatus.READY || dao.getSegments(recordingId).isEmpty()) {
            _messages.emit("Finish transcription before running AI Enhance.")
            return@launchHandled
        }
        if (services.secrets.get("gemini").isNullOrBlank()) {
            _messages.emit("Add your Gemini API key in Settings before running AI Enhance.")
            return@launchHandled
        }
        AiEnhanceWorker.enqueueAuto(app, recordingId, allowMobileData = settings.allowMobileData)
        _messages.emit("AI Enhance queued.")
    }

    fun runEnhanceSelection(startMs: Long, endMs: Long) = launchHandled("Couldn't start Enhance Selection.") {
        val settings = services.settings.settings.first()
        val start = minOf(startMs, endMs).coerceAtLeast(0L)
        val end = maxOf(startMs, endMs)
        if (end - start > EnhanceConstants.SELECTION_SOFT_CAP_MS) {
            _messages.emit("Enhance Selection is capped at 15 minutes. Split this range into smaller parts.")
            return@launchHandled
        }
        if (services.secrets.get("gemini").isNullOrBlank()) {
            _messages.emit("Add your Gemini API key in Settings before running AI Enhance.")
            return@launchHandled
        }
        AiEnhanceWorker.enqueueSelection(app, recordingId, settings.allowMobileData, start, end)
        _messages.emit("Enhance Selection queued for ${formatTimestamp(start)} to ${formatTimestamp(end)}.")
    }

    fun setSkipAiPass(skip: Boolean) = launchHandled("Couldn't update Skip AI Pass.") {
        val current = dao.getAiRecording(recordingId) ?: AiRecordingEntity(recordingId)
        dao.upsertAiRecording(
            current.copy(
                skipAiPass = skip,
                status = if (skip) AiPassStatus.SKIPPED else if (current.status == AiPassStatus.SKIPPED) AiPassStatus.NOT_RUN else current.status,
            )
        )
        refreshDerivedData()
        _messages.emit(if (skip) "AI pass skipped for this recording." else "AI pass enabled for this recording.")
    }

    fun confirmSuggestion(suggestion: SpeakerSuggestionEntity) = launchHandled("Couldn't confirm the speaker suggestion.") {
        dao.upsertAlias(SpeakerAliasEntity(recordingId, suggestion.speakerId, suggestion.suggestedName))
        dao.deleteSpeakerSuggestion(recordingId, suggestion.speakerId)
        refreshDerivedData()
        _messages.emit("Speaker name confirmed.")
    }

    fun acceptRepair(repairId: String) = launchHandled("Couldn't accept the repair.") {
        services.aiEnhance.setRepairApplied(repairId, true)
        _messages.emit("Repair accepted.")
    }

    fun revertRepair(repairId: String) = launchHandled("Couldn't revert the repair.") {
        services.aiEnhance.setRepairReverted(repairId, true)
        _messages.emit("Repair reverted.")
    }

    fun undoRename() = launchHandled("Couldn't undo the recording rename.") {
        services.aiPass.undoRename(recordingId)
        _messages.emit("Recording name restored.")
    }

    fun mergeWithNext(setId: String) = launchHandled("Couldn't merge the conversation sets.") {
        val sets = dao.getConversationSets(recordingId)
        val index = sets.indexOfFirst { it.id == setId }
        if (index !in 0 until sets.lastIndex) return@launchHandled
        val first = sets[index]
        val second = sets[index + 1]
        val merged = first.copy(
            endMs = second.endMs,
            summary = listOf(first.summary, second.summary).filter(String::isNotBlank).joinToString(" "),
            speakerIds = (first.speakerIds.split('|') + second.speakerIds.split('|'))
                .filter(String::isNotBlank).distinct().joinToString("|"),
        )
        persistSets(sets.take(index) + merged + sets.drop(index + 2))
        _messages.emit("Conversation sets merged.")
    }

    fun splitSet(setId: String, timestampMs: Long) = launchHandled("Couldn't split the conversation set.") {
        val sets = dao.getConversationSets(recordingId)
        val index = sets.indexOfFirst { it.id == setId }
        if (index < 0) return@launchHandled
        val target = sets[index]
        if (timestampMs <= target.startMs + 1_000 || timestampMs >= target.endMs - 1_000) return@launchHandled
        val first = target.copy(endMs = timestampMs - 1, summary = "")
        val second = target.copy(
            id = UUID.randomUUID().toString(),
            startMs = timestampMs,
            title = target.title + " (continued)",
            summary = "",
        )
        persistSets(sets.take(index) + first + second + sets.drop(index + 1))
        _messages.emit("Conversation set split.")
    }

    suspend fun search(query: String): List<SearchHit> = try {
        withContext(Dispatchers.IO) { services.search.search(query, recordingId) }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Log.e("DebriefSearch", "Recording search failed", error)
        _messages.emit(userMessage("Search is temporarily unavailable.", error))
        emptyList()
    }

    suspend fun exportMarkdown(): String = withContext(Dispatchers.IO) {
        val current = dao.getRecording(recordingId) ?: return@withContext ""
        val aliases = dao.getAliases(recordingId).associate { it.speakerId to it.displayName }
        val comments = dao.getComments(recordingId)
        buildString {
            appendLine("# ${current.displayName}")
            appendLine()
            dao.getSegments(recordingId).forEach { segment ->
                val time = formatTimestamp(segment.startMs)
                appendLine("**[$time] ${aliases[segment.speakerId] ?: segment.speakerId}:** ${segment.text}")
                comments.filter { it.timestampMs in segment.startMs..segment.endMs }.forEach {
                    appendLine()
                    appendLine("> **Comment [${formatTimestamp(it.timestampMs)}]:** ${it.text}")
                }
                appendLine()
            }
            val unmatched = comments.filter { comment -> state.value.segments.none { comment.timestampMs in it.startMs..it.endMs } }
            if (unmatched.isNotEmpty()) {
                appendLine("## Comments")
                unmatched.forEach { appendLine("- [${formatTimestamp(it.timestampMs)}] ${it.text}") }
            }
        }
    }

    private suspend fun refreshDerivedData() {
        try {
            services.search.rebuild(recordingId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("DebriefSearch", "Derived search update failed", error)
            _messages.emit("Saved on device, but the search index couldn't update yet.")
        }
        try {
            val folder = services.settings.settings.first().folderUri
            folder?.let { uri ->
                DocumentFile.fromTreeUri(app, Uri.parse(uri))?.let { services.sidecars.write(it, recordingId) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("DebriefSidecar", "Sidecar update failed", error)
            _messages.emit("Saved on device, but the sidecar backup couldn't update. Re-link the folder to retry.")
        }
    }

    private suspend fun persistSets(sets: List<ConversationSetEntity>) {
        dao.replaceConversationSets(recordingId, sets.mapIndexed { index, set -> set.copy(orderIndex = index) })
        refreshDerivedData()
    }

    private fun aiConfigurationError(settings: AppSettings): String? {
        val secretName = when (settings.aiProvider) {
            "openai" -> "openai_compatible"
            "anthropic" -> "anthropic"
            else -> "gemini"
        }
        val providerLabel = when (settings.aiProvider) {
            "openai" -> "OpenAI-compatible"
            "anthropic" -> "Anthropic"
            else -> "Gemini"
        }
        if (services.secrets.get(secretName).isNullOrBlank()) {
            return "Add your " + providerLabel + " API key in Settings before running the AI pass."
        }
        if (settings.aiProvider == "openai") {
            if (!settings.openAiBaseUrl.startsWith("https://")) return "Enter an HTTPS base URL for the OpenAI-compatible provider."
            if (settings.openAiModel.isBlank()) return "Enter a model name for the OpenAI-compatible provider."
        }
        if (settings.aiProvider == "anthropic" && settings.anthropicModel.isBlank()) {
            return "Enter a Claude model name in Settings."
        }
        return null
    }

    private fun launchHandled(fallback: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("DebriefReview", fallback, error)
            _messages.emit(userMessage(fallback, error))
        }
    }

    companion object {
        fun factory(application: Application, recordingId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReviewViewModel(application, recordingId) as T
            }
    }
}

fun formatTimestamp(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
