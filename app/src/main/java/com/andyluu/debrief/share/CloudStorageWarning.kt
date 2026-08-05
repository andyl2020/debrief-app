package com.andyluu.debrief.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andyluu.debrief.MainActivity
import com.andyluu.debrief.R
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val CLOUD_STORAGE_WARNING_BYTES = 9_000_000_000L

enum class CloudStorageWarningLevel { NEAR_LIMIT, EXCEEDED }

data class CloudStorageWarning(
    val level: CloudStorageWarningLevel,
    val deadlineMillis: Long,
)

fun cloudStorageWarning(
    currentBytes: Long,
    referenceBytes: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): CloudStorageWarning? {
    if (currentBytes < CLOUD_STORAGE_WARNING_BYTES || referenceBytes <= 0L) return null
    val month = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zoneId))
    val deadline = month.atEndOfMonth()
        .atTime(23, 59, 59)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    return CloudStorageWarning(
        level = if (currentBytes >= referenceBytes) CloudStorageWarningLevel.EXCEEDED else CloudStorageWarningLevel.NEAR_LIMIT,
        deadlineMillis = deadline,
    )
}

class CloudStorageWarningNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun update(currentBytes: Long, referenceBytes: Long, nowMillis: Long = System.currentTimeMillis()) {
        val warning = cloudStorageWarning(currentBytes, referenceBytes, nowMillis)
        if (warning == null) {
            manager.cancel(NOTIFICATION_ID)
            preferences.edit().clear().apply()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val zone = ZoneId.systemDefault()
        val monthKey = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone)).toString()
        val levelKey = warning.level.name
        val lastNotifiedAt = preferences.getLong(KEY_LAST_NOTIFIED_AT, 0L)
        val unchanged = preferences.getString(KEY_MONTH, null) == monthKey &&
            preferences.getString(KEY_LEVEL, null) == levelKey
        if (unchanged && nowMillis - lastNotifiedAt < NOTIFICATION_REPEAT_MS) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cloud storage warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings when Debrief sharing approaches the Cloudflare R2 free allowance"
            }
        )
        val deadline = Instant.ofEpochMilli(warning.deadlineMillis).atZone(zone).toLocalDate()
            .format(DateTimeFormatter.ofPattern("MMM d"))
        val used = String.format("%.1f GB", currentBytes / 1_000_000_000.0)
        val title = if (warning.level == CloudStorageWarningLevel.EXCEEDED) {
            "Cloud storage may incur charges"
        } else {
            "Cloud storage is near its free allowance"
        }
        val message = "$used used. Reduce storage before $deadline to lower this month's average and avoid R2 charges."
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SHARED_LINKS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "$message Cloudflare measures GB-month from average daily peak storage; deleting files does not erase usage already accrued this month."
                ))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
        preferences.edit()
            .putString(KEY_MONTH, monthKey)
            .putString(KEY_LEVEL, levelKey)
            .putLong(KEY_LAST_NOTIFIED_AT, nowMillis)
            .apply()
    }

    private companion object {
        const val CHANNEL_ID = "cloud_storage_warnings"
        const val NOTIFICATION_ID = 2401
        const val PREFERENCES = "cloud_storage_warning"
        const val KEY_MONTH = "month"
        const val KEY_LEVEL = "level"
        const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
        const val NOTIFICATION_REPEAT_MS = 24 * 60 * 60 * 1_000L
    }
}
