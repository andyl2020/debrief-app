package com.andyluu.debrief.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andyluu.debrief.DebriefApplication

class AiPassWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val force = inputData.getBoolean(KEY_FORCE, false)
        return runCatching {
            (applicationContext as DebriefApplication).services.aiPass.run(recordingId, force)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (runAttemptCount < 2 && error !is AiPassException) Result.retry()
                else Result.failure(Data.Builder().putString("error", error.message?.take(300)).build())
            },
        )
    }

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        private const val KEY_FORCE = "force"

        fun enqueue(context: Context, recordingId: String, allowMobileData: Boolean, force: Boolean = false) {
            val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<AiPassWorker>()
                .setInputData(Data.Builder().putString(KEY_RECORDING_ID, recordingId).putBoolean(KEY_FORCE, force).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .addTag("ai-pass")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ai-pass-" + recordingId,
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

