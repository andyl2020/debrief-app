package com.andyluu.debrief.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andyluu.debrief.DebriefApplication

class RecordingNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISSED) return
        val application = context.applicationContext as? DebriefApplication ?: return
        application.services.recorder.markNotificationDismissed()
    }

    companion object {
        const val ACTION_DISMISSED = "com.andyluu.debrief.recording.NOTIFICATION_DISMISSED"
    }
}
