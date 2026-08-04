package com.andyluu.debrief.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
        return try {
            setForeground(shareUploadForegroundInfo(applicationContext, "Preparing private share", 0, 1))
            repository.processDraft(draftId) { stage, completed, total ->
                setProgress(workDataOf("stage" to stage, "completed" to completed, "total" to total))
                setForeground(shareUploadForegroundInfo(applicationContext, stage, completed, total))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudShareException) {
            val canRetry = error.retryable && runAttemptCount < 5
            repository.recordFailure(draftId, error.message, resumable = error.retryable)
            if (canRetry) Result.retry() else Result.failure(workDataOf("error" to error.message))
        } catch (error: Throwable) {
            val message = error.message?.takeIf(String::isNotBlank) ?: "The private share could not be prepared."
            repository.recordFailure(draftId, message, resumable = false)
            Result.failure(workDataOf("error" to message))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        shareUploadForegroundInfo(applicationContext, "Preparing private share", 0, 1)

    companion object {
        const val KEY_DRAFT_ID = "draft_id"
    }
}

internal fun shareUploadForegroundInfo(
    context: Context,
    stage: String,
    completed: Int,
    total: Int,
): ForegroundInfo {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(SHARE_CHANNEL_ID, "Share preparation", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Resumable private set preparation and upload"
        }
    )
    val safeTotal = total.coerceAtLeast(1)
    val notification = NotificationCompat.Builder(context, SHARE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Creating Debrief share")
        .setContentText(stage)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(safeTotal, completed.coerceIn(0, safeTotal), false)
        .build()
    return ForegroundInfo(
        SHARE_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}

private const val SHARE_CHANNEL_ID = "share_uploads"
private const val SHARE_NOTIFICATION_ID = 2301
