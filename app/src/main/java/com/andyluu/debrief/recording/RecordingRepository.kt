package com.andyluu.debrief.recording

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordingRepository(private val context: Context) {
    private val sessionStore = RecordingSessionStore(context)
    private val mutableState = MutableStateFlow(sessionStore.load())

    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    internal fun update(next: RecordingState, persist: Boolean = true) {
        mutableState.value = next
        if (persist) sessionStore.save(next)
    }

    internal fun updateAmplitude(amplitude: Float) {
        mutableState.value = mutableState.value.copy(amplitude = amplitude.coerceIn(0f, 1f))
    }

    fun start(folderUri: String, requestedName: String = RecordingNames.newDisplayName()) {
        if (!state.value.canStart) return
        startService(
            RecordingService.ACTION_START,
            RecordingService.EXTRA_FOLDER_URI to folderUri,
            RecordingService.EXTRA_DISPLAY_NAME to RecordingNames.normalizeDisplayName(requestedName),
        )
    }

    fun pause() = startService(RecordingService.ACTION_PAUSE)
    fun resume() = startService(RecordingService.ACTION_RESUME)
    fun stop() = startService(RecordingService.ACTION_STOP)
    fun discard() {
        if (state.value.phase !in setOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED)) return
        startService(RecordingService.ACTION_DISCARD)
    }

    fun retrySave(folderUri: String) {
        if (state.value.phase != RecordingPhase.SAVE_FAILED) return
        startService(
            RecordingService.ACTION_RETRY_SAVE,
            RecordingService.EXTRA_FOLDER_URI to folderUri,
        )
    }

    fun recoverInterruptedIfNeeded() {
        val current = state.value
        if (current.sessionId != null && current.phase in setOf(
                RecordingPhase.PREPARING,
                RecordingPhase.RECORDING,
                RecordingPhase.PAUSED,
                RecordingPhase.FINALIZING,
                RecordingPhase.RECOVERING,
            )
        ) {
            startService(RecordingService.ACTION_RECOVER)
        }
    }

    fun reportPermissionDenied() {
        update(
            state.value.copy(
                phase = RecordingPhase.IDLE,
                statusMessage = "Microphone permission is required to record.",
            )
        )
    }

    fun clearMessage() {
        update(state.value.copy(statusMessage = null), persist = state.value.sessionId != null)
    }

    fun updateDisplayName(requestedName: String) {
        val current = state.value
        if (current.sessionId == null ||
            current.phase in setOf(RecordingPhase.FINALIZING, RecordingPhase.RECOVERING)
        ) return
        val displayName = RecordingNames.normalizeDisplayName(requestedName)
        if (displayName != current.displayName) {
            update(current.copy(displayName = displayName))
        }
    }

    internal fun markNotificationDismissed() {
        val current = state.value
        if (current.sessionId != null && !current.notificationDismissed) {
            update(current.copy(notificationDismissed = true))
        }
    }

    private fun startService(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(context, RecordingService::class.java).setAction(action)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { error ->
                Log.e("DebriefRecorder", "Could not deliver recorder action $action", error)
                val current = state.value
                update(
                    current.copy(
                        phase = if (action == RecordingService.ACTION_START) RecordingPhase.IDLE else current.phase,
                        statusMessage = when (error) {
                            is SecurityException -> "Android denied microphone/background recording access. Check Debrief permissions and try again."
                            else -> "Android could not start the recorder service. Reopen Debrief and try again."
                        },
                    )
                )
            }
    }
}

private class RecordingSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("recording_session", Context.MODE_PRIVATE)

    fun load(): RecordingState {
        val phase = preferences.getString("phase", null)
            ?.let { runCatching { RecordingPhase.valueOf(it) }.getOrNull() }
            ?: RecordingPhase.IDLE
        val pauseReason = preferences.getString("pause_reason", null)
            ?.let { runCatching { RecordingPauseReason.valueOf(it) }.getOrNull() }
            ?: RecordingPauseReason.NONE
        return RecordingState(
            phase = phase,
            sessionId = preferences.getString("session_id", null),
            displayName = preferences.getString("display_name", null),
            folderUri = preferences.getString("folder_uri", null),
            startedAtEpochMs = preferences.getLong("started_at", 0),
            elapsedBeforeRunningMs = preferences.getLong("elapsed", 0),
            runningSinceElapsedMs = 0,
            pauseReason = pauseReason,
            statusMessage = preferences.getString("message", null),
            lastSavedName = preferences.getString("last_saved_name", null),
            lastSavedUri = preferences.getString("last_saved_uri", null),
            notificationDismissed = preferences.getBoolean("notification_dismissed", false),
        )
    }

    fun save(state: RecordingState) {
        val editor = preferences.edit()
            .putString("phase", state.phase.name)
            .putString("session_id", state.sessionId)
            .putString("display_name", state.displayName)
            .putString("folder_uri", state.folderUri)
            .putLong("started_at", state.startedAtEpochMs)
            .putLong("elapsed", state.elapsedMs())
            .putString("pause_reason", state.pauseReason.name)
            .putString("message", state.statusMessage)
            .putString("last_saved_name", state.lastSavedName)
            .putString("last_saved_uri", state.lastSavedUri)
            .putBoolean("notification_dismissed", state.notificationDismissed)
        if (!editor.commit()) {
            Log.e("DebriefRecorder", "Could not checkpoint the recording recovery state.")
        }
    }
}
