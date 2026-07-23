package com.andyluu.debrief.recording

import android.os.SystemClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class RecordingPhase {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    FINALIZING,
    RECOVERING,
    SAVE_FAILED,
}

enum class RecordingPauseReason {
    NONE,
    USER,
    CALL,
    STORAGE,
}

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sessionId: String? = null,
    val displayName: String? = null,
    val folderUri: String? = null,
    val startedAtEpochMs: Long = 0,
    val elapsedBeforeRunningMs: Long = 0,
    val runningSinceElapsedMs: Long = 0,
    val pauseReason: RecordingPauseReason = RecordingPauseReason.NONE,
    val amplitude: Float = 0f,
    val statusMessage: String? = null,
    val lastSavedName: String? = null,
    val lastSavedUri: String? = null,
) {
    val isSessionActive: Boolean
        get() = phase in setOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.PAUSED,
            RecordingPhase.FINALIZING,
            RecordingPhase.RECOVERING,
        )

    val canStart: Boolean
        get() = phase == RecordingPhase.IDLE

    fun elapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val running = if (phase == RecordingPhase.RECORDING && runningSinceElapsedMs > 0) {
            (nowElapsedMs - runningSinceElapsedMs).coerceAtLeast(0)
        } else {
            0
        }
        return (elapsedBeforeRunningMs + running).coerceAtLeast(0)
    }
}

internal object RecordingNames {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

    fun newSessionId(nowMs: Long = System.currentTimeMillis()): String =
        "rec-$nowMs"

    fun newDisplayName(now: LocalDateTime = LocalDateTime.now()): String =
        "Debrief ${formatter.format(now)}.m4a"

    fun partFileName(sessionId: String, index: Int): String =
        "$sessionId-part-${index.toString().padStart(4, '0')}.m4a"
}
