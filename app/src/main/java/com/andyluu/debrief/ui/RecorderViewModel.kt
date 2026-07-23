package com.andyluu.debrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.recording.RecordingNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val recorder = (application as DebriefApplication).services.recorder
    val state = recorder.state
    private val mutableName = MutableStateFlow(RecordingNames.editableBase(RecordingNames.newDisplayName()))
    val name = mutableName.asStateFlow()

    init {
        recorder.recoverInterruptedIfNeeded()
        viewModelScope.launch {
            var previousSessionId: String? = null
            state.collect { current ->
                if (current.sessionId != null && current.displayName != null) {
                    mutableName.value = RecordingNames.editableBase(current.displayName)
                } else if (previousSessionId != null) {
                    mutableName.value = RecordingNames.editableBase(RecordingNames.newDisplayName())
                }
                previousSessionId = current.sessionId
            }
        }
    }

    fun updateName(value: String) {
        val sanitized = RecordingNames.sanitizeEditableBase(value)
        mutableName.value = sanitized
        if (sanitized.isNotBlank()) {
            recorder.updateDisplayName(RecordingNames.normalizeDisplayName(sanitized))
        }
    }

    fun start(folderUri: String) {
        val requestedName = RecordingNames.normalizeDisplayName(mutableName.value)
        recorder.start(folderUri, requestedName)
    }
    fun pause() = recorder.pause()
    fun resume() = recorder.resume()
    fun stop() = recorder.stop()
    fun retrySave(folderUri: String) = recorder.retrySave(folderUri)
    fun permissionDenied() = recorder.reportPermissionDenied()
    fun clearMessage() = recorder.clearMessage()
}
