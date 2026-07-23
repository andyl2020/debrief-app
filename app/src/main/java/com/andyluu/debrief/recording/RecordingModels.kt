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
    val notificationDismissed: Boolean = false,
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
    private val invalidFilenameCharacters = Regex("""[\\/:*?"<>|]""")
    private val repeatedWhitespace = Regex("\\s+")
    private val knownAudioExtensions = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

    fun newSessionId(nowMs: Long = System.currentTimeMillis()): String =
        "rec-$nowMs"

    fun newDisplayName(now: LocalDateTime = LocalDateTime.now()): String =
        "Debrief ${formatter.format(now)}.m4a"

    fun editableBase(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return if (extension in knownAudioExtensions) displayName.substringBeforeLast('.') else displayName
    }

    fun sanitizeEditableBase(value: String): String =
        value
            .replace(invalidFilenameCharacters, "-")
            .replace(repeatedWhitespace, " ")
            .trimStart(' ', '.', '-')
            .take(120)

    fun normalizeDisplayName(requestedName: String, originalExtension: String = "m4a"): String {
        val trimmed = requestedName.trim()
        val requestedExtension = trimmed.substringAfterLast('.', "").lowercase()
        val requestedBase = if (requestedExtension in knownAudioExtensions) {
            trimmed.substringBeforeLast('.')
        } else {
            trimmed
        }
        val base = sanitizeEditableBase(requestedBase).trimEnd(' ', '.', '-')
        require(base.isNotBlank()) { "Enter a recording name." }
        val extension = originalExtension
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .ifBlank { "m4a" }
        return "$base.$extension"
    }

    fun partFileName(sessionId: String, index: Int): String =
        "$sessionId-part-${index.toString().padStart(4, '0')}.m4a"
}
