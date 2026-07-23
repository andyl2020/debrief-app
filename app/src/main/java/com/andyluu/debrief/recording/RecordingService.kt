package com.andyluu.debrief.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.MainActivity
import com.andyluu.debrief.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

class RecordingService : Service() {
    private val repository by lazy { (application as DebriefApplication).services.recorder }
    private val output by lazy { RecordingOutput(this) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaRecorder: MediaRecorder? = null
    private var sessionId: String? = null
    private var currentPartIndex = 0
    private var pendingNextPart: File? = null
    private var finalizing = false
    private var lastStorageCheckElapsedMs = 0L
    private var lastEncoderRestartElapsedMs = 0L
    private var consecutiveEncoderRestarts = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureSilenced = false
    private var foregroundStarted = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val ownConfiguration = mediaRecorder?.activeRecordingConfiguration
                ?: configs.orEmpty().firstOrNull()
            val silenced = ownConfiguration?.isClientSilenced == true
            if (silenced == captureSilenced) return
            captureSilenced = silenced
            val current = repository.state.value
            if (current.phase != RecordingPhase.RECORDING) return
            repository.update(
                current.copy(
                    statusMessage = if (silenced) {
                        "Another app has the microphone. Android is temporarily supplying silence; capture will return automatically."
                    } else {
                        null
                    }
                )
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RECOVER
        if (action == ACTION_NOTIFICATION_DISMISSED) {
            repository.markNotificationDismissed()
            return START_STICKY
        }
        val foregroundResult = runCatching {
            if (!repository.state.value.notificationDismissed || !foregroundStarted) {
                showForeground(useMicrophone = action == ACTION_START || mediaRecorder != null)
            }
        }
        if (foregroundResult.isFailure) {
            val message = "Android denied foreground microphone access. Saving everything captured so far."
            if (mediaRecorder != null && repository.state.value.sessionId != null) {
                repository.update(repository.state.value.copy(statusMessage = message))
                finishRecording()
            } else {
                failWithoutSession("Android denied foreground microphone access. Check Debrief permissions and try again.")
            }
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startRecording(
                folderUri = intent?.getStringExtra(EXTRA_FOLDER_URI),
                requestedName = intent?.getStringExtra(EXTRA_DISPLAY_NAME),
            )
            ACTION_PAUSE -> {
                if (mediaRecorder == null && repository.state.value.isSessionActive) recoverInterrupted()
                else pauseInternal(RecordingPauseReason.USER)
            }
            ACTION_RESUME -> {
                if (mediaRecorder == null && repository.state.value.isSessionActive) recoverInterrupted()
                else resumeInternal()
            }
            ACTION_STOP -> {
                if (mediaRecorder == null && repository.state.value.isSessionActive) recoverInterrupted()
                else finishRecording()
            }
            ACTION_RETRY_SAVE -> retrySave(intent?.getStringExtra(EXTRA_FOLDER_URI))
            ACTION_RECOVER -> recoverInterrupted()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(monitorRunnable)
        if (mediaRecorder != null && !finalizing) {
            runCatching { mediaRecorder?.stop() }
            runCatching { mediaRecorder?.unregisterAudioRecordingCallback(recordingCallback) }
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRecording(folderUri: String?, requestedName: String?) {
        if (!repository.state.value.canStart || mediaRecorder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failWithoutSession("Microphone permission is required to record.")
            return
        }
        if (folderUri.isNullOrBlank()) {
            failWithoutSession("Choose a recordings folder before starting.")
            return
        }
        val localDirectory = File(getExternalFilesDir(null) ?: filesDir, "recording-sessions")
        val availableBytes = StatFs(localDirectory.apply { mkdirs() }.absolutePath).availableBytes
        if (availableBytes < MIN_START_FREE_BYTES) {
            failWithoutSession("Not enough free device storage to start safely. Free at least 256 MB and try again.")
            return
        }

        val now = System.currentTimeMillis()
        sessionId = RecordingNames.newSessionId(now)
        currentPartIndex = 1
        acquireWakeLock()
        repository.update(
            RecordingState(
                phase = RecordingPhase.PREPARING,
                sessionId = sessionId,
                displayName = runCatching {
                    RecordingNames.normalizeDisplayName(requestedName ?: RecordingNames.newDisplayName())
                }.getOrElse { RecordingNames.newDisplayName() },
                folderUri = folderUri,
                startedAtEpochMs = now,
                statusMessage = "Preparing microphone…",
                notificationDismissed = false,
            )
        )

        runCatching { createAndStartRecorder(currentPartIndex) }
            .onSuccess {
                repository.update(
                    repository.state.value.copy(
                        phase = RecordingPhase.RECORDING,
                        runningSinceElapsedMs = SystemClock.elapsedRealtime(),
                        pauseReason = RecordingPauseReason.NONE,
                        statusMessage = null,
                    )
                )
                showForeground(useMicrophone = true)
                handler.removeCallbacks(monitorRunnable)
                handler.post(monitorRunnable)
            }
            .onFailure { error ->
                runCatching { mediaRecorder?.unregisterAudioRecordingCallback(recordingCallback) }
                runCatching { mediaRecorder?.release() }
                mediaRecorder = null
                releaseWakeLock()
                failSession("Recording could not start: ${error.message ?: "microphone unavailable"}")
            }
    }

    private fun createAndStartRecorder(partIndex: Int) {
        val id = requireNotNull(sessionId)
        val part = output.partFile(id, partIndex)
        if (part.exists()) check(part.delete()) { "Could not prepare local recording storage." }

        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(48_000)
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setMaxFileSize(PART_MAX_BYTES)
        recorder.setOutputFile(part.absolutePath)
        recorder.setOnErrorListener { _, what, extra -> handleRecorderError("Audio encoder error $what/$extra") }
        recorder.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                    runCatching { queueNextPart(recorder) }
                        .onFailure { handleRecorderError("Could not prepare the next long-recording part") }
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> handleNextPartStarted()
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                    handleRecorderError("The recorder reached its local part limit before rollover")
            }
        }
        recorder.prepare()
        recorder.registerAudioRecordingCallback(ContextCompat.getMainExecutor(this), recordingCallback)
        mediaRecorder = recorder
        recorder.start()
    }

    private fun queueNextPart(recorder: MediaRecorder) {
        if (pendingNextPart != null) return
        val next = output.partFile(requireNotNull(sessionId), currentPartIndex + 1)
        if (next.exists()) runCatching { next.delete() }
        recorder.setNextOutputFile(next)
        pendingNextPart = next
    }

    private fun handleNextPartStarted() {
        currentPartIndex += 1
        pendingNextPart = null
        repository.update(
            repository.state.value.copy(statusMessage = "Long recording secured in $currentPartIndex local parts.")
        )
    }

    private fun pauseInternal(reason: RecordingPauseReason) {
        val current = repository.state.value
        if (current.phase == RecordingPhase.PAUSED) {
            if (reason == RecordingPauseReason.USER && current.pauseReason != RecordingPauseReason.USER) {
                repository.update(current.copy(pauseReason = RecordingPauseReason.USER, statusMessage = "Paused"))
            }
            return
        }
        if (current.phase != RecordingPhase.RECORDING) return
        runCatching { mediaRecorder?.pause() }
            .onSuccess {
                repository.update(
                    current.copy(
                        phase = RecordingPhase.PAUSED,
                        elapsedBeforeRunningMs = current.elapsedMs(),
                        runningSinceElapsedMs = 0,
                        pauseReason = reason,
                        amplitude = 0f,
                        statusMessage = when (reason) {
                            RecordingPauseReason.CALL -> "Paused for a call. Debrief will resume automatically."
                            RecordingPauseReason.STORAGE -> "Paused because device storage is low. Free space to resume automatically."
                            else -> "Paused"
                        },
                    )
                )
                updateNotification()
            }
            .onFailure { handleRecorderError("Could not pause the recorder") }
    }

    private fun resumeInternal() {
        val current = repository.state.value
        if (current.phase != RecordingPhase.PAUSED) return
        if (isCallOrCommunicationActive()) {
            repository.update(current.copy(pauseReason = RecordingPauseReason.CALL, statusMessage = "Waiting for the call to end…"))
            return
        }
        if (availableLocalBytes() < MIN_RESUME_FREE_BYTES) {
            repository.update(
                current.copy(
                    pauseReason = RecordingPauseReason.STORAGE,
                    statusMessage = "Free at least 64 MB of device storage to resume.",
                )
            )
            return
        }
        runCatching { mediaRecorder?.resume() }
            .onSuccess {
                repository.update(
                    current.copy(
                        phase = RecordingPhase.RECORDING,
                        runningSinceElapsedMs = SystemClock.elapsedRealtime(),
                        pauseReason = RecordingPauseReason.NONE,
                        statusMessage = null,
                    )
                )
                updateNotification()
            }
            .onFailure { handleRecorderError("Could not resume the recorder") }
    }

    private fun finishRecording() {
        val current = repository.state.value
        if (current.sessionId == null || finalizing) return
        finalizing = true
        handler.removeCallbacks(monitorRunnable)
        val elapsed = current.elapsedMs()
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        pendingNextPart = null
        releaseWakeLock()
        repository.update(
            current.copy(
                phase = RecordingPhase.FINALIZING,
                elapsedBeforeRunningMs = elapsed,
                runningSinceElapsedMs = 0,
                amplitude = 0f,
                statusMessage = "Saving to the linked folder…",
            )
        )
        showForeground(useMicrophone = false)
        exportSession(current.sessionId, current.folderUri, current.displayName)
    }

    private fun retrySave(folderOverride: String?) {
        val current = repository.state.value
        val id = current.sessionId ?: return failWithoutSession("There is no local recording to retry.")
        val targetFolder = folderOverride?.takeIf(String::isNotBlank) ?: current.folderUri
        repository.update(
            current.copy(
                phase = RecordingPhase.RECOVERING,
                folderUri = targetFolder,
                statusMessage = "Retrying save…",
            )
        )
        showForeground(useMicrophone = false)
        exportSession(id, targetFolder, current.displayName)
    }

    private fun recoverInterrupted() {
        if (mediaRecorder != null || finalizing) return
        val current = repository.state.value
        val id = current.sessionId
        if (id == null) {
            stopForegroundAndSelf()
            return
        }
        if (output.sessionParts(id).none(M4aConcatenator::isReadableAudio)) {
            output.cleanup(id)
            repository.update(
                RecordingState(
                    phase = RecordingPhase.IDLE,
                    statusMessage = "Android interrupted capture before a playable recovery part was finalized.",
                )
            )
            stopForegroundAndSelf()
            return
        }
        finalizing = true
        repository.update(
            current.copy(
                phase = RecordingPhase.RECOVERING,
                runningSinceElapsedMs = 0,
                amplitude = 0f,
                statusMessage = "Recovering a recording interrupted by Android…",
            )
        )
        showForeground(useMicrophone = false)
        exportSession(id, current.folderUri, current.displayName)
    }

    private fun exportSession(id: String, treeUri: String?, requestedName: String?) {
        serviceScope.launch {
            runCatching {
                check(!treeUri.isNullOrBlank()) { "Choose a recordings folder, then retry the save." }
                val (source, parts) = output.prepareForExport(id)
                output.saveToFolder(
                    source = source,
                    treeUri = treeUri,
                    requestedName = requestedName ?: RecordingNames.newDisplayName(),
                    partCount = parts.size,
                )
            }.onSuccess { saved ->
                output.cleanup(id)
                runCatching {
                    (application as DebriefApplication).services.folders.scan(Uri.parse(treeUri))
                }
                repository.update(
                    RecordingState(
                        phase = RecordingPhase.IDLE,
                        statusMessage = if (saved.partCount > 1) {
                            "Saved ${saved.displayName}. ${saved.partCount} protected local parts were joined without re-encoding."
                        } else {
                            "Saved ${saved.displayName}."
                        },
                        lastSavedName = saved.displayName,
                        lastSavedUri = saved.uri.toString(),
                    )
                )
                finalizing = false
                stopForegroundAndSelf()
            }.onFailure { error ->
                val previous = repository.state.value
                repository.update(
                    previous.copy(
                        phase = RecordingPhase.SAVE_FAILED,
                        runningSinceElapsedMs = 0,
                        amplitude = 0f,
                        statusMessage = "Recording preserved locally. ${error.message ?: "Saving failed."}",
                    )
                )
                finalizing = false
                updateNotification()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun handleRecorderError(message: String) {
        if (finalizing || mediaRecorder == null) return
        val now = SystemClock.elapsedRealtime()
        consecutiveEncoderRestarts = if (now - lastEncoderRestartElapsedMs < 60_000) {
            consecutiveEncoderRestarts + 1
        } else {
            1
        }
        lastEncoderRestartElapsedMs = now
        val current = repository.state.value
        val elapsed = current.elapsedMs()
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        pendingNextPart?.let { if (it.length() == 0L) runCatching { it.delete() } }
        pendingNextPart = null

        if (consecutiveEncoderRestarts <= 2) {
            currentPartIndex += 1
            runCatching { createAndStartRecorder(currentPartIndex) }
                .onSuccess {
                    repository.update(
                        current.copy(
                            phase = RecordingPhase.RECORDING,
                            elapsedBeforeRunningMs = elapsed,
                            runningSinceElapsedMs = SystemClock.elapsedRealtime(),
                            pauseReason = RecordingPauseReason.NONE,
                            statusMessage = "$message. Debrief restarted capture automatically.",
                        )
                    )
                    return
                }
        }
        repository.update(
            current.copy(
                elapsedBeforeRunningMs = elapsed,
                runningSinceElapsedMs = 0,
                statusMessage = "$message. Saving everything captured so far…",
            )
        )
        finishRecording()
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            val current = repository.state.value
            if (mediaRecorder == null || current.phase !in setOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED)) return

            if (current.phase == RecordingPhase.RECORDING && isCallOrCommunicationActive()) {
                pauseInternal(RecordingPauseReason.CALL)
            } else if (current.phase == RecordingPhase.PAUSED &&
                current.pauseReason == RecordingPauseReason.CALL &&
                !isCallOrCommunicationActive()
            ) {
                resumeInternal()
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastStorageCheckElapsedMs >= STORAGE_CHECK_INTERVAL_MS) {
                lastStorageCheckElapsedMs = now
                val available = availableLocalBytes()
                if (repository.state.value.phase == RecordingPhase.RECORDING && available < MIN_PAUSE_FREE_BYTES) {
                    pauseInternal(RecordingPauseReason.STORAGE)
                } else if (repository.state.value.phase == RecordingPhase.PAUSED &&
                    repository.state.value.pauseReason == RecordingPauseReason.STORAGE &&
                    available >= MIN_RESUME_FREE_BYTES
                ) {
                    resumeInternal()
                }
            }

            val amplitude = if (repository.state.value.phase == RecordingPhase.RECORDING) {
                runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            } else {
                0
            }
            repository.updateAmplitude(sqrt(amplitude.coerceAtLeast(0) / 32767f))
            updateNotification()
            handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    private fun isCallOrCommunicationActive(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= 30 && mode == AudioManager.MODE_CALL_SCREENING)
    }

    private fun availableLocalBytes(): Long {
        val directory = File(getExternalFilesDir(null) ?: filesDir, "recording-sessions").apply { mkdirs() }
        return runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Debrief:LongRecording").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun failWithoutSession(message: String) {
        repository.update(RecordingState(phase = RecordingPhase.IDLE, statusMessage = message))
        stopForegroundAndSelf()
    }

    private fun failSession(message: String) {
        val current = repository.state.value
        val id = current.sessionId
        val hasPlayableAudio = id != null && output.sessionParts(id).any(M4aConcatenator::isReadableAudio)
        if (!hasPlayableAudio) {
            id?.let(output::cleanup)
            repository.update(RecordingState(phase = RecordingPhase.IDLE, statusMessage = message))
        } else {
            repository.update(current.copy(phase = RecordingPhase.SAVE_FAILED, statusMessage = message))
        }
        stopForegroundAndSelf()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Active recordings",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps long Debrief recordings running while the app is in the background."
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun showForeground(useMicrophone: Boolean) {
        if (repository.state.value.notificationDismissed && foregroundStarted) return
        val type = if (useMicrophone) {
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else 0
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            type,
        )
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (repository.state.value.notificationDismissed) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = repository.state.value
        val openIntent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_RECORDER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .setOngoing(state.isSessionActive)
            .setDeleteIntent(servicePendingIntent(ACTION_NOTIFICATION_DISMISSED, 4))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle(
                when (state.phase) {
                    RecordingPhase.RECORDING -> "Debrief is recording"
                    RecordingPhase.PAUSED -> "Debrief recording paused"
                    RecordingPhase.FINALIZING -> "Saving recording"
                    RecordingPhase.RECOVERING -> "Recovering recording"
                    RecordingPhase.SAVE_FAILED -> "Recording needs attention"
                    else -> "Debrief Recorder"
                }
            )
            .setContentText(state.statusMessage ?: formatElapsed(state.elapsedMs()))

        when (state.phase) {
            RecordingPhase.RECORDING -> builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                servicePendingIntent(ACTION_PAUSE, 2),
            )
            RecordingPhase.PAUSED -> builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                servicePendingIntent(ACTION_RESUME, 3),
            )
            else -> Unit
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun formatElapsed(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    companion object {
        const val ACTION_START = "com.andyluu.debrief.recording.START"
        const val ACTION_PAUSE = "com.andyluu.debrief.recording.PAUSE"
        const val ACTION_RESUME = "com.andyluu.debrief.recording.RESUME"
        const val ACTION_STOP = "com.andyluu.debrief.recording.STOP"
        const val ACTION_RETRY_SAVE = "com.andyluu.debrief.recording.RETRY_SAVE"
        const val ACTION_RECOVER = "com.andyluu.debrief.recording.RECOVER"
        const val ACTION_NOTIFICATION_DISMISSED = "com.andyluu.debrief.recording.NOTIFICATION_DISMISSED"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_DISPLAY_NAME = "display_name"

        private const val NOTIFICATION_CHANNEL = "debrief_recording"
        internal const val NOTIFICATION_ID = 8_120
        // At 128 kbps this rolls over about every nine minutes. Android switches to the
        // queued file without stopping capture, so completed parts remain recoverable.
        private const val PART_MAX_BYTES = 8L * 1024L * 1024L
        private const val MIN_START_FREE_BYTES = 256L * 1024L * 1024L
        private const val MIN_PAUSE_FREE_BYTES = 32L * 1024L * 1024L
        private const val MIN_RESUME_FREE_BYTES = 64L * 1024L * 1024L
        private const val MONITOR_INTERVAL_MS = 500L
        private const val STORAGE_CHECK_INTERVAL_MS = 15_000L
        private const val MAX_WAKE_LOCK_MS = 12L * 60L * 60L * 1_000L
    }
}
