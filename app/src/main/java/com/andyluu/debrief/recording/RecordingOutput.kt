package com.andyluu.debrief.recording

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

internal data class RecordingSaveResult(
    val displayName: String,
    val uri: Uri,
    val partCount: Int,
)

internal class RecordingOutput(private val context: Context) {
    private val recordingDirectory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "recording-sessions")
            .apply { mkdirs() }

    fun partFile(sessionId: String, index: Int): File =
        File(recordingDirectory, RecordingNames.partFileName(sessionId, index))

    fun sessionParts(sessionId: String): List<File> =
        recordingDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("$sessionId-part-") && it.extension == "m4a" }
            .sortedBy { it.name }

    fun prepareForExport(sessionId: String): Pair<File, List<File>> {
        val readable = sessionParts(sessionId).filter(M4aConcatenator::isReadableAudio)
        require(readable.isNotEmpty()) {
            "No playable audio could be recovered. Keep Debrief installed and try restarting the phone before deleting app data."
        }
        if (readable.size == 1) return readable.first() to readable
        val joined = File(recordingDirectory, "$sessionId-joined.m4a")
        M4aConcatenator.concatenate(readable, joined)
        return joined to readable
    }

    fun saveToFolder(source: File, treeUri: String, requestedName: String, partCount: Int): RecordingSaveResult {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("The linked recordings folder is unavailable.")
        check(root.exists() && root.canWrite()) {
            "Debrief no longer has write access to the linked folder. Choose the folder again, then retry."
        }
        val displayName = availableName(root, requestedName)
        val destination = root.createFile("audio/mp4", displayName)
            ?: error("Android could not create the recording in the linked folder.")
        try {
            val descriptor = context.contentResolver.openFileDescriptor(destination.uri, "w")
                ?: error("Android could not open the new recording for writing.")
            descriptor.use { parcel ->
                FileOutputStream(parcel.fileDescriptor).use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output, 256 * 1024) }
                    output.flush()
                    parcel.fileDescriptor.sync()
                }
            }
        } catch (error: Throwable) {
            runCatching { destination.delete() }
            throw error
        }
        return RecordingSaveResult(displayName, destination.uri, partCount)
    }

    fun cleanup(sessionId: String) {
        recordingDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(sessionId) }
            .forEach { runCatching { it.delete() } }
    }

    private fun availableName(root: DocumentFile, requestedName: String): String {
        if (root.findFile(requestedName) == null) return requestedName
        val base = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "")
        var counter = 2
        while (true) {
            val candidate = if (extension.isBlank()) "$base ($counter)" else "$base ($counter).$extension"
            if (root.findFile(candidate) == null) return candidate
            counter++
        }
    }
}
