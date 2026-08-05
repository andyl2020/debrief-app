package com.andyluu.debrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.data.AppSettings
import com.andyluu.debrief.data.CloudUsageEntity
import com.andyluu.debrief.data.ShareDraftEntity
import com.andyluu.debrief.data.SharedLinkEntity
import com.andyluu.debrief.share.ShareSelectionPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareReviewUiState(
    val loading: Boolean = true,
    val preview: ShareSelectionPreview? = null,
    val paired: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
)

class ShareReviewViewModel(
    application: Application,
    private val recordingId: String,
    private val setIds: List<String>,
) : AndroidViewModel(application) {
    private val repository = (application as DebriefApplication).services.shares
    private val _state = MutableStateFlow(ShareReviewUiState())
    val state: StateFlow<ShareReviewUiState> = _state

    init {
        viewModelScope.launch {
            runCatching { repository.preview(recordingId, setIds) }
                .onSuccess { preview ->
                    _state.value = ShareReviewUiState(
                        loading = false,
                        preview = preview,
                        paired = repository.isPaired(),
                    )
                }
                .onFailure { error ->
                    _state.value = ShareReviewUiState(
                        loading = false,
                        error = error.message ?: "These sets could not be prepared for sharing.",
                    )
                }
        }
    }

    fun create(title: String, expiryDays: Int, pin: String?, onCreated: (String) -> Unit) {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.createAndEnqueue(recordingId, setIds, title, expiryDays, pin)
            }.onSuccess { draftId ->
                _state.update { it.copy(creating = false) }
                onCreated(draftId)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        creating = false,
                        paired = repository.isPaired(),
                        error = error.message ?: "The private share could not be started.",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(application: Application, recordingId: String, setIds: List<String>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShareReviewViewModel(application, recordingId, setIds) as T
            }
    }
}

data class CloudSharingUiState(
    val settings: AppSettings = AppSettings(),
    val paired: Boolean = false,
    val usage: CloudUsageEntity? = null,
    val drafts: List<ShareDraftEntity> = emptyList(),
    val links: List<SharedLinkEntity> = emptyList(),
    val refreshing: Boolean = false,
    val pairing: Boolean = false,
)

class CloudSharingViewModel(application: Application) : AndroidViewModel(application) {
    private val services = (application as DebriefApplication).services
    private val repository = services.shares
    private val paired = MutableStateFlow(repository.isPaired())
    private val refreshing = MutableStateFlow(false)
    private val pairing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<CloudSharingUiState> = combine(
        services.settings.settings,
        repository.cloudUsage,
        repository.drafts,
        repository.sharedLinks,
        combine(paired, refreshing, pairing) { isPaired, isRefreshing, isPairing ->
            Triple(isPaired, isRefreshing, isPairing)
        },
    ) { settings, usage, drafts, links, activity ->
        CloudSharingUiState(
            settings = settings,
            paired = activity.first,
            usage = usage,
            drafts = drafts,
            links = links,
            refreshing = activity.second,
            pairing = activity.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSharingUiState(paired = repository.isPaired()))

    init {
        if (paired.value) {
            viewModelScope.launch {
                runCatching { repository.recoverPending() }
                    .onFailure { _messages.emit(it.message ?: "A pending share could not be resumed.") }
            }
            refresh()
        }
    }

    fun pair(baseUrl: String, code: String) {
        if (pairing.value) return
        pairing.value = true
        viewModelScope.launch {
            runCatching { repository.pair(baseUrl, code) }
                .onSuccess {
                    paired.value = true
                    _messages.emit("Cloud sharing is connected.")
                }
                .onFailure { _messages.emit(it.message ?: "Cloud sharing could not be connected.") }
            pairing.value = false
        }
    }

    fun refresh() {
        if (!paired.value || refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            runCatching { repository.refresh() }
                .onFailure { _messages.emit(it.message ?: "Cloud sharing could not be refreshed.") }
            refreshing.value = false
        }
    }

    fun resume(draftId: String) {
        repository.resume(draftId)
        _messages.tryEmit("Share preparation resumed.")
    }

    fun cancel(draftId: String) = launchAction("Draft removed.") { repository.cancel(draftId) }

    fun extend(shareId: String, expiryDays: Int) =
        launchAction("Link expiry updated.") { repository.extend(shareId, expiryDays) }

    fun revoke(shareId: String) = launchAction("Link revoked.") { repository.revoke(shareId) }

    private fun launchAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _messages.emit(success) }
                .onFailure { _messages.emit(it.message ?: "The cloud action failed.") }
        }
    }
}
