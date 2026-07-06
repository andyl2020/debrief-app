package com.andyluu.debrief.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class FolderRepository(
    private val context: Context,
    private val dao: DebriefDao,
    private val sidecarStore: SidecarStore,
) {
    private val extensions = setOf("mp3", "m4a", "wav", "aac")

    suspend fun scan(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("The selected folder is no longer available")
        val audioFiles = mutableListOf<DocumentFile>()
        Log.i("DebriefScan", "Scanning readable=${root.canRead()} existing=${root.exists()} children=${root.listFiles().size}")
        collectAudio(root, audioFiles)
        Log.i("DebriefScan", "Found ${audioFiles.size} supported recordings")
        val activeIds = mutableListOf<String>()

        audioFiles.forEach { file ->
            val uri = file.uri.toString()
            val id = sha256(uri)
            activeIds += id
            val existing = dao.getRecording(id)
            val recording = RecordingEntity(
                id = id,
                documentUri = uri,
                displayName = file.name ?: "Untitled recording",
                mimeType = file.type,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                durationMs = existing?.durationMs?.takeIf { it > 0 } ?: readDuration(file.uri),
                status = existing?.status ?: RecordingStatus.NEW,
                errorMessage = existing?.errorMessage,
                playbackPositionMs = existing?.playbackPositionMs ?: 0,
                discoveredAt = existing?.discoveredAt ?: System.currentTimeMillis(),
            )
            dao.upsertRecording(recording)
            if (existing == null) sidecarStore.restoreIfPresent(root, recording)
        }

        if (activeIds.isEmpty()) dao.deleteAllRecordings() else dao.deleteMissingRecordings(activeIds)
        audioFiles.size
    }

    private fun collectAudio(directory: DocumentFile, target: MutableList<DocumentFile>) {
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectAudio(file, target)
                file.isFile && file.name?.substringAfterLast('.', "")?.lowercase() in extensions -> target += file
            }
        }
    }

    private fun readDuration(uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
