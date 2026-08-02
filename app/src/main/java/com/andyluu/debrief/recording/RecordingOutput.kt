package com.andyluu.debrief.recording

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

internal data class RecordingSaveResult(
    val displayName: String,
    val uri: Uri,
    val partCount: Int,
)

internal data class RecordingSaveProgress(
    val fraction: Float,
    val message: String,
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

    fun saveSessionToFolder(
        sessionId: String,
        treeUri: String,
        requestedName: String,
        onProgress: (RecordingSaveProgress) -> Unit = {},
    ): RecordingSaveResult {
        val readable = sessionParts(sessionId).filter(M4aConcatenator::isReadableAudio)
        require(readable.isNotEmpty()) {
            "No playable audio could be recovered. Keep Debrief installed and try restarting the phone before deleting app data."
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("The linked recordings folder is unavailable.")
        check(root.exists() && root.canWrite()) {
            "Debrief no longer has write access to the linked folder. Choose the folder again, then retry."
        }
        val displayName = availableName(root, requestedName)
        var destination = root.createFile("audio/mp4", displayName)
            ?: error("Android could not create the recording in the linked folder.")
        try {
            onProgress(RecordingSaveProgress(0f, "Preparing ${readable.size} protected audio parts..."))
            if (readable.size == 1) {
                copyToDestination(
                    source = readable.first(),
                    destination = destination,
                    progressStart = 0.02f,
                    progressEnd = 0.96f,
                    onProgress = onProgress,
                )
            } else if (!concatenateDirectly(readable, destination, onProgress)) {
                // Some document providers expose a write-only pipe instead of a seekable
                // file descriptor. MediaMuxer cannot write MP4 to those providers, so use
                // the slower two-pass compatibility path only for that unusual case.
                check(destination.delete()) {
                    "The linked folder cannot accept a finalized recording. Choose a local device folder and retry."
                }
                val joined = File(recordingDirectory, "$sessionId-joined.m4a")
                try {
                    M4aConcatenator.concatenate(readable, joined) { completed, total ->
                        onProgress(
                            RecordingSaveProgress(
                                fraction = 0.02f + (completed.toFloat() / total) * 0.46f,
                                message = "Preparing audio part $completed of $total...",
                            )
                        )
                    }
                    destination = root.createFile("audio/mp4", displayName)
                        ?: error("Android could not create the recording in the linked folder.")
                    copyToDestination(
                        source = joined,
                        destination = destination,
                        progressStart = 0.50f,
                        progressEnd = 0.96f,
                        onProgress = onProgress,
                    )
                } finally {
                    runCatching { joined.delete() }
                }
            }

            onProgress(RecordingSaveProgress(0.98f, "Verifying the saved recording..."))
            check(isReadableAudio(destination.uri)) {
                "Android wrote the recording, but Debrief could not verify it. The protected local parts were kept for retry."
            }
        } catch (error: Throwable) {
            runCatching { destination.delete() }
            throw error
        }
        onProgress(RecordingSaveProgress(1f, "Recording saved."))
        return RecordingSaveResult(displayName, destination.uri, readable.size)
    }

    fun cleanup(sessionId: String) {
        recordingDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(sessionId) }
            .forEach { runCatching { it.delete() } }
    }

    private fun concatenateDirectly(
        parts: List<File>,
        destination: DocumentFile,
        onProgress: (RecordingSaveProgress) -> Unit,
    ): Boolean {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(destination.uri, "rw")
        }.getOrNull() ?: return false
        descriptor.use { parcel ->
            if (!isSeekable(parcel.fileDescriptor)) return false
            Os.lseek(parcel.fileDescriptor, 0L, OsConstants.SEEK_SET)
            M4aConcatenator.concatenate(parts, parcel.fileDescriptor) { completed, total ->
                onProgress(
                    RecordingSaveProgress(
                        fraction = 0.02f + (completed.toFloat() / total) * 0.94f,
                        message = "Saving audio part $completed of $total...",
                    )
                )
            }
            parcel.fileDescriptor.sync()
        }
        return true
    }

    private fun copyToDestination(
        source: File,
        destination: DocumentFile,
        progressStart: Float,
        progressEnd: Float,
        onProgress: (RecordingSaveProgress) -> Unit,
    ) {
        val descriptor = context.contentResolver.openFileDescriptor(destination.uri, "w")
            ?: error("Android could not open the new recording for writing.")
        descriptor.use { parcel ->
            FileOutputStream(parcel.fileDescriptor).use { output ->
                source.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    val totalBytes = source.length().coerceAtLeast(1L)
                    var copiedBytes = 0L
                    var lastReportedFraction = -1f
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        val copiedFraction = (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        if (copiedFraction - lastReportedFraction >= PROGRESS_REPORT_STEP || copiedBytes >= totalBytes) {
                            lastReportedFraction = copiedFraction
                            onProgress(
                                RecordingSaveProgress(
                                    fraction = progressStart + copiedFraction * (progressEnd - progressStart),
                                    message = "Saving recording... ${(copiedFraction * 100).toInt()}%",
                                )
                            )
                        }
                    }
                }
                output.flush()
                parcel.fileDescriptor.sync()
            }
        }
    }

    private fun isReadableAudio(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
            M4aConcatenator.isReadableAudio(parcel.fileDescriptor)
        } == true
    }.getOrDefault(false)

    private fun isSeekable(fileDescriptor: java.io.FileDescriptor): Boolean = runCatching {
        Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_CUR)
        true
    }.getOrDefault(false)

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

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val PROGRESS_REPORT_STEP = 0.01f
    }
}
