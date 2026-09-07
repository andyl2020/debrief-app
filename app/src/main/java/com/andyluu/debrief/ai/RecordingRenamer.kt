package com.andyluu.debrief.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.andyluu.debrief.data.DebriefDao
import com.andyluu.debrief.recording.RecordingNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingRenamer(
    private val context: Context,
    private val dao: DebriefDao,
) {
    suspend fun rename(recordingId: String, requestedName: String): String = withContext(Dispatchers.IO) {
        val recording = dao.getRecording(recordingId) ?: throw AiPassException("Recording not found")
        val sourceUri = Uri.parse(recording.documentUri)
        val file = DocumentFile.fromSingleUri(context, sourceUri)
            ?: throw AiPassException("The recording file is no longer available")
        val originalExtension = recording.displayName.substringAfterLast('.', "")
        val finalName = RecordingNames.normalizeDisplayName(requestedName, originalExtension)
        if (finalName == recording.displayName) return@withContext finalName
        if (!file.exists()) throw AiPassException("The recording file is no longer available. Refresh the folder and try again.")
        if (!file.canWrite()) throw AiPassException("Debrief no longer has permission to rename this file. Re-link the recordings folder and try again.")
        if (!DocumentsContract.isDocumentUri(context, sourceUri)) {
            throw AiPassException("This storage provider does not support safe file renaming. Move the recording into the linked folder and try again.")
        }

        // DocumentFile.fromSingleUri(...).renameTo(...) is intentionally unsupported
        // by AndroidX and throws UnsupportedOperationException. Recordings discovered
        // through a linked SAF tree are still normal document URIs, so use the platform
        // contract directly and retain the new URI returned by providers that change it.
        val renamedUri = try {
            DocumentsContract.renameDocument(context.contentResolver, sourceUri, finalName)
        } catch (_: SecurityException) {
            throw AiPassException("Debrief no longer has permission to rename this file. Re-link the recordings folder and try again.")
        } catch (_: UnsupportedOperationException) {
            throw AiPassException("This storage provider does not support renaming files. Choose a writable local folder and try again.")
        } ?: throw AiPassException("Android could not rename the recording file. Make sure another file does not already use that name.")

        val renamedFile = DocumentFile.fromSingleUri(context, renamedUri)
        val storedName = renamedFile?.name?.takeIf(String::isNotBlank) ?: finalName
        val lastModified = renamedFile?.lastModified()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        try {
            dao.updateRecordingLocation(
                id = recordingId,
                documentUri = renamedUri.toString(),
                displayName = storedName,
                lastModified = lastModified,
            )
        } catch (error: Exception) {
            // Avoid leaving the physical file renamed while the library still points at
            // the old URI if the local database update unexpectedly fails.
            runCatching {
                DocumentsContract.renameDocument(context.contentResolver, renamedUri, recording.displayName)
            }
            throw error
        }
        storedName
    }
}
