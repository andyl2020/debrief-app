package com.andyluu.debrief.enhance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit

class AiEnhanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing AI Enhance…")

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: MODE_AUTO
        return try {
            setForeground(foregroundInfo("Enhancing transcript…"))
            val processor = (applicationContext as DebriefApplication).services.aiEnhance
            if (mode == MODE_SELECTION) {
                processor.runSelection(
                    recordingId,
                    inputData.getLong(KEY_START_MS, 0L),
                    inputData.getLong(KEY_END_MS, 0L),
                )
            } else {
                processor.runAuto(recordingId)
            }
            Result.success()
        } catch (error: Throwable) {
            val retryable = error !is AiEnhanceException || error.retryable
            val message = error.message?.take(300) ?: "AI Enhance failed"
            if (runAttemptCount < 4 && retryable) Result.retry()
            else Result.failure(Data.Builder().putString("error", message).build())
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI Enhance", NotificationManager.IMPORTANCE_LOW)
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

    private fun recordingIdForNotification() = inputData.getString(KEY_RECORDING_ID)?.hashCode()?.and(0x7fffffff) ?: 84

    companion object {
        private const val CHANNEL_ID = "debrief_ai_enhance"
        private const val KEY_RECORDING_ID = "recording_id"
        private const val KEY_MODE = "mode"
        private const val KEY_START_MS = "start_ms"
        private const val KEY_END_MS = "end_ms"
        private const val MODE_AUTO = "auto"
        private const val MODE_SELECTION = "selection"

        fun enqueueAuto(context: Context, recordingId: String, allowMobileData: Boolean) {
            enqueue(context, recordingId, allowMobileData, Data.Builder().putString(KEY_RECORDING_ID, recordingId).putString(KEY_MODE, MODE_AUTO).build())
        }

        fun enqueueSelection(context: Context, recordingId: String, allowMobileData: Boolean, startMs: Long, endMs: Long) {
            enqueue(
                context,
                recordingId,
                allowMobileData,
                Data.Builder()
                    .putString(KEY_RECORDING_ID, recordingId)
                    .putString(KEY_MODE, MODE_SELECTION)
                    .putLong(KEY_START_MS, startMs)
                    .putLong(KEY_END_MS, endMs)
                    .build(),
            )
        }

        private fun enqueue(context: Context, recordingId: String, allowMobileData: Boolean, data: Data) {
            val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<AiEnhanceWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-enhance")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ai-enhance-$recordingId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
