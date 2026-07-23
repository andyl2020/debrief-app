package com.andyluu.debrief.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.andyluu.debrief.DebriefApplication

class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val recorder = (application as DebriefApplication).services.recorder
    val state = recorder.state

    init {
        recorder.recoverInterruptedIfNeeded()
    }

    fun start(folderUri: String) = recorder.start(folderUri)
    fun pause() = recorder.pause()
    fun resume() = recorder.resume()
    fun stop() = recorder.stop()
    fun retrySave(folderUri: String) = recorder.retrySave(folderUri)
    fun permissionDenied() = recorder.reportPermissionDenied()
    fun clearMessage() = recorder.clearMessage()
}
