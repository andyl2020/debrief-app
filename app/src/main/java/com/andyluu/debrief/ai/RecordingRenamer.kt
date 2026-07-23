package com.andyluu.debrief.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andyluu.debrief.data.DebriefDao
import com.andyluu.debrief.recording.RecordingNames

class RecordingRenamer(
    private val context: Context,
    private val dao: DebriefDao,
) {
    suspend fun rename(recordingId: String, requestedName: String): String {
        val recording = dao.getRecording(recordingId) ?: throw AiPassException("Recording not found")
        val file = DocumentFile.fromSingleUri(context, Uri.parse(recording.documentUri))
            ?: throw AiPassException("The recording file is no longer available")
        val originalExtension = recording.displayName.substringAfterLast('.', "")
        val finalName = RecordingNames.normalizeDisplayName(requestedName, originalExtension)
        if (finalName == recording.displayName) return finalName
        if (!file.renameTo(finalName)) throw AiPassException("Android could not rename the recording file")
        dao.updateRecordingLocation(
            id = recordingId,
            documentUri = file.uri.toString(),
            displayName = file.name ?: finalName,
            lastModified = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        return file.name ?: finalName
    }
}
