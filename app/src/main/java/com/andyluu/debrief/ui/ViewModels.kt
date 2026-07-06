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
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.SearchHit
import com.andyluu.debrief.data.SpeakerAliasEntity
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.transcription.TranscriptionWorker
import com.andyluu.debrief.transcription.ApiUsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ReviewState(
    val recording: RecordingEntity? = null,
    val segments: List<TranscriptSegmentEntity> = emptyList(),
    val comments: List<CommentEntity> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
)

data class UsageUiState(
    val loading: Boolean = false,
    val snapshot: ApiUsageSnapshot? = null,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DebriefApplication
    private val services = app.services
    private val dao = services.database.dao()

    val recordings: StateFlow<List<RecordingEntity>> = dao.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = services.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _usage = MutableStateFlow(UsageUiState())
    val usage = _usage.asStateFlow()

    init {
        viewModelScope.launch {
            services.settings.settings.map { it.folderUri }.filterNotNull().distinctUntilChanged().collect { uri ->
                runCatching { services.folders.scan(Uri.parse(uri)) }
                    .onFailure { Log.e("DebriefScan", "Folder refresh failed", it) }
            }
        }
    }

    fun linkFolder(uri: Uri, onComplete: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                services.settings.setFolderUri(uri.toString())
                services.folders.scan(uri)
            }
            result.exceptionOrNull()?.let { Log.e("DebriefScan", "Folder link failed", it) }
            onComplete(result)
        }
    }

    fun scan() {
        val uri = settings.value.folderUri ?: return
        viewModelScope.launch {
            runCatching { services.folders.scan(Uri.parse(uri)) }
                .onFailure { Log.e("DebriefScan", "Folder refresh failed", it) }
        }
    }

    fun transcribe(recordingIds: Collection<String>) {
        val eligible = recordings.value
            .filter { it.id in recordingIds && (it.status == RecordingStatus.NEW || it.status == RecordingStatus.FAILED) }
        viewModelScope.launch {
            eligible.forEach { recording ->
                dao.updateStatus(recording.id, RecordingStatus.QUEUED)
                TranscriptionWorker.enqueue(app, recording.id, settings.value.allowMobileData)
            }
        }
    }

    suspend fun search(query: String, recordingId: String? = null): List<SearchHit> =
        withContext(Dispatchers.IO) { services.search.search(query, recordingId) }

    fun saveDeepgramKey(value: String) {
        services.secrets.put("deepgram", value.trim())
        if (settings.value.provider == "deepgram") refreshUsage("deepgram")
    }
    fun saveAssemblyAiKey(value: String) {
        services.secrets.put("assemblyai", value.trim())
        if (settings.value.provider == "assemblyai") refreshUsage("assemblyai")
    }
    fun hasDeepgramKey() = services.secrets.has("deepgram")
    fun hasAssemblyAiKey() = services.secrets.has("assemblyai")
    fun setMobileData(value: Boolean) = viewModelScope.launch { services.settings.setAllowMobileData(value) }
    fun setKeyterms(value: String) = viewModelScope.launch { services.settings.setKeyterms(value) }
    fun setProvider(value: String) = viewModelScope.launch {
        services.settings.setProvider(value)
        refreshUsage(value)
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
}

class ReviewViewModel(
    application: Application,
    private val recordingId: String,
) : AndroidViewModel(application) {
    private val app = application as DebriefApplication
    private val services = app.services
    private val dao = services.database.dao()
    private val _reloadVersion = MutableStateFlow(0)
    val reloadVersion = _reloadVersion.asStateFlow()

    val state: StateFlow<ReviewState> = combine(
        dao.observeRecording(recordingId),
        dao.observeSegments(recordingId),
        dao.observeComments(recordingId),
        dao.observeAliases(recordingId),
    ) { recording, segments, comments, aliases ->
        ReviewState(recording, segments, comments, aliases.associate { it.speakerId to it.displayName })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun savePlaybackPosition(positionMs: Long) = viewModelScope.launch {
        dao.updatePlaybackPosition(recordingId, positionMs)
    }

    fun reloadTranscript() = viewModelScope.launch {
        val recording = dao.getRecording(recordingId)
        if (recording != null && dao.getSegments(recordingId).isEmpty()) {
            services.settings.settings.first().folderUri?.let { folderUri ->
                DocumentFile.fromTreeUri(app, Uri.parse(folderUri))?.let { root ->
                    services.sidecars.restoreIfPresent(root, recording)
                }
            }
        }
        _reloadVersion.update { it + 1 }
    }

    fun addComment(timestampMs: Long, text: String) = viewModelScope.launch {
        dao.upsertComment(
            CommentEntity(
                id = UUID.randomUUID().toString(),
                recordingId = recordingId,
                timestampMs = timestampMs,
                text = text.trim(),
            )
        )
        refreshDerivedData()
    }

    fun editComment(comment: CommentEntity, text: String) = viewModelScope.launch {
        dao.upsertComment(comment.copy(text = text.trim(), updatedAt = System.currentTimeMillis()))
        refreshDerivedData()
    }

    fun deleteComment(commentId: String) = viewModelScope.launch {
        dao.deleteComment(commentId)
        refreshDerivedData()
    }

    fun renameSpeaker(speakerId: String, displayName: String) = viewModelScope.launch {
        dao.upsertAlias(SpeakerAliasEntity(recordingId, speakerId, displayName.trim()))
        refreshDerivedData()
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        services.search.search(query, recordingId)
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
        services.search.rebuild(recordingId)
        val folder = services.settings.settings.stateIn(viewModelScope).value.folderUri
        folder?.let { uri ->
            DocumentFile.fromTreeUri(app, Uri.parse(uri))?.let { services.sidecars.write(it, recordingId) }
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
