package com.andyluu.debrief.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.R
import com.andyluu.debrief.data.RecordingStatus
import kotlinx.coroutines.flow.first

class TranscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val services = (appContext as DebriefApplication).services

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing recording…")

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val dao = services.database.dao()
        val recording = dao.getRecording(recordingId) ?: return Result.failure()
        setForeground(foregroundInfo("Transcribing ${recording.displayName}"))
        dao.updateStatus(recordingId, RecordingStatus.TRANSCRIBING)
        var compressed: java.io.File? = null
        return try {
            val settings = services.settings.settings.first()
            val providerName = settings.provider
            val key = services.secrets.get(providerName)
                ?: throw TranscriptionException("Add the ${providerName.replaceFirstChar(Char::uppercase)} API key in Settings")
            compressed = AudioCompressor.compress(applicationContext, Uri.parse(recording.documentUri), recordingId)
            val provider: TranscriptionProvider = when (providerName) {
                "assemblyai" -> AssemblyAiProvider()
                else -> DeepgramProvider()
            }
            val result = provider.transcribe(
                context = applicationContext,
                recordingId = recordingId,
                audioFile = compressed,
                mimeType = "audio/mp4",
                apiKey = key,
                keyterms = settings.keyterms.lines().flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank),
            )
            dao.replaceTranscript(recordingId, result.segments, result.words)
            services.usage.recordSuccess(providerName, key, recording.durationMs)
            dao.updateStatus(recordingId, RecordingStatus.READY)
            services.search.rebuild(recordingId)
            settings.folderUri?.let { uri ->
                DocumentFile.fromTreeUri(applicationContext, Uri.parse(uri))?.let { services.sidecars.write(it, recordingId) }
            }
            Result.success()
        } catch (error: Throwable) {
            val message = error.message?.take(300) ?: "Transcription failed"
            dao.updateStatus(recordingId, RecordingStatus.FAILED, message)
            if (runAttemptCount < 3 && error !is TranscriptionException) Result.retry()
            else Result.failure(Data.Builder().putString("error", message).build())
        } finally {
            compressed?.delete()
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Debrief")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            recordingIdForNotification(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun recordingIdForNotification() = inputData.getString(KEY_RECORDING_ID)?.hashCode()?.and(0x7fffffff) ?: 47

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        private const val CHANNEL_ID = "debrief_transcription"

        fun enqueue(context: Context, recordingId: String, allowMobileData: Boolean) {
            val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(Data.Builder().putString(KEY_RECORDING_ID, recordingId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .addTag("transcription")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcribe-$recordingId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
