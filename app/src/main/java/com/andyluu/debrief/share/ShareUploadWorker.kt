package com.andyluu.debrief.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.R
import kotlinx.coroutines.CancellationException

class ShareUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext.applicationContext as DebriefApplication).services.shares

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        setForeground(notification("Preparing private share", 0, 1))
        return try {
            repository.processDraft(draftId) { stage, completed, total ->
                setProgress(workDataOf("stage" to stage, "completed" to completed, "total" to total))
                setForeground(notification(stage, completed, total))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudShareException) {
            val canRetry = error.retryable && runAttemptCount < 5
            repository.recordFailure(draftId, error.message, resumable = true)
            if (canRetry) Result.retry() else Result.failure(workDataOf("error" to error.message))
        } catch (error: Throwable) {
            val message = error.message?.takeIf(String::isNotBlank) ?: "The private share could not be prepared."
            repository.recordFailure(draftId, message, resumable = false)
            Result.failure(workDataOf("error" to message))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notification("Preparing private share", 0, 1)

    private fun notification(stage: String, completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Share preparation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Resumable private set preparation and upload"
            }
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Creating Debrief share")
            .setContentText(stage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_DRAFT_ID = "draft_id"
        private const val CHANNEL_ID = "share_uploads"
        private const val NOTIFICATION_ID = 2301
    }
}
