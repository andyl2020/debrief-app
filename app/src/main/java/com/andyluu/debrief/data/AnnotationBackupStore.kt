package com.andyluu.debrief.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AnnotationBackupStatus(
    val protectedItemCount: Int = 0,
    val localCurrent: Boolean = false,
    val folderCurrent: Boolean = false,
    val localRevision: Long = 0,
    val folderRevision: Long = 0,
    val localError: String? = null,
    val folderError: String? = null,
) {
    val warning: String?
        get() = when {
            protectedItemCount == 0 -> null
            !localCurrent ->
                "Your markers are still in Debrief's encrypted database, but the extra local recovery copy could not be updated. Avoid clearing app data and tap Retry backup."
            !folderCurrent ->
                "Your chapters, bookmarks, redactions, and speaker names are saved locally, but the recording-folder backup is out of date. Re-link the folder or tap Retry backup."
            else -> null
        }
}

@Serializable
internal data class AnnotationSnapshot(
    val schemaVersion: Int = 1,
    val recordingId: String,
    val recordingName: String,
    val recordingSizeBytes: Long,
    val recordingDurationMs: Long,
    val revision: Long,
    val comments: List<SidecarComment>,
    val redactions: List<SidecarRedaction>,
    val speakerAliases: Map<String, String>,
    val sets: List<SidecarSet>,
) {
    val protectedItemCount: Int
        get() = comments.size + redactions.size + speakerAliases.size + sets.size
}

/**
 * Maintains an encrypted, app-private recovery copy of user-authored recording
 * metadata. Room remains authoritative; this is a second copy used only if those
 * rows disappear unexpectedly. The recording-folder sidecar is the third copy
 * and is tracked separately because it can survive an uninstall.
 */
class AnnotationBackupStore(
    context: Context,
    database: DebriefDatabase,
) {
    private val appContext = context.applicationContext
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = File(appContext.filesDir, "annotation-backups").apply { mkdirs() }
    private val preferences = appContext.getSharedPreferences("annotation_backup_status", Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(appContext)
    private val stateFlows = ConcurrentHashMap<String, MutableStateFlow<AnnotationBackupStatus>>()
    private val mutex = Mutex()

    fun observeStatus(recordingId: String): StateFlow<AnnotationBackupStatus> =
        stateFlows.getOrPut(recordingId) { MutableStateFlow(loadStatus(recordingId)) }.asStateFlow()

    fun currentStatus(recordingId: String): AnnotationBackupStatus =
        stateFlows.getOrPut(recordingId) { MutableStateFlow(loadStatus(recordingId)) }.value

    suspend fun checkpoint(recordingId: String): AnnotationBackupStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            val recording = dao.getRecording(recordingId)
            if (recording == null) {
                return@withLock updateStatus(
                    recordingId,
                    currentStatus(recordingId).copy(
                        localCurrent = false,
                        localError = "Recording is no longer in the local database.",
                    ),
                )
            }
            val previous = currentStatus(recordingId)
            val revision = maxOf(System.currentTimeMillis(), previous.localRevision + 1)
            val snapshot = buildSnapshot(recording, revision)
            val pending = previous.copy(
                protectedItemCount = snapshot.protectedItemCount,
                localCurrent = false,
                folderCurrent = snapshot.protectedItemCount == 0,
                localRevision = revision,
                localError = null,
                folderError = null,
            )
            updateStatus(recordingId, pending)
            runCatching { writeEncryptedAtomically(snapshot) }
                .fold(
                    onSuccess = {
                        updateStatus(recordingId, pending.copy(localCurrent = true))
                    },
                    onFailure = { error ->
                        updateStatus(
                            recordingId,
                            pending.copy(
                                localCurrent = false,
                                localError = error.message?.take(180) ?: "Local backup write failed.",
                            ),
                        )
                    },
                )
        }
    }

    fun recordFolderResult(
        recordingId: String,
        revision: Long,
        success: Boolean,
        error: Throwable? = null,
    ): AnnotationBackupStatus {
        val current = currentStatus(recordingId)
        val next = if (success) {
            current.copy(
                folderCurrent = current.protectedItemCount == 0 || revision == current.localRevision,
                folderRevision = revision,
                folderError = null,
            )
        } else {
            current.copy(
                folderCurrent = current.protectedItemCount == 0,
                folderError = error?.message?.take(180) ?: "Recording-folder backup is unavailable.",
            )
        }
        return updateStatus(recordingId, next)
    }

    suspend fun restoreIfEmpty(recording: RecordingEntity): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = readSnapshot(recording.id) ?: return@withLock false
            if (snapshot.recordingId != recording.id || snapshot.recordingSizeBytes != recording.sizeBytes) {
                return@withLock false
            }
            snapshot.comments.forEach {
                dao.upsertComment(CommentEntity(it.id, recording.id, it.timestampMs, it.text, it.createdAt, it.updatedAt))
            }
            if (snapshot.redactions.isNotEmpty()) {
                dao.insertRedactions(snapshot.redactions.map {
                    RedactionEntity(it.id, recording.id, it.startMs, it.endMs, it.text, it.createdAt)
                })
            }
            snapshot.speakerAliases.forEach { (speakerId, name) ->
                dao.upsertAlias(SpeakerAliasEntity(recording.id, speakerId, name))
            }
            dao.replaceConversationSets(
                recording.id,
                snapshot.sets.map {
                    ConversationSetEntity(it.id, recording.id, it.orderIndex, it.startMs, it.endMs, it.title, it.summary, it.speakerIds)
                },
            )
            updateStatus(
                recording.id,
                currentStatus(recording.id).copy(
                    protectedItemCount = snapshot.protectedItemCount,
                    localCurrent = true,
                    localRevision = snapshot.revision,
                    localError = null,
                ),
            )
            snapshot.protectedItemCount > 0
        }
    }

    internal fun deleteForTest(recordingId: String) {
        snapshotFile(recordingId).delete()
        previousSnapshotFile(recordingId).delete()
        preferences.edit().remove(statusKey(recordingId)).commit()
        stateFlows.remove(recordingId)
    }

    internal fun snapshotPayloadForTest(recordingId: String): ByteArray? =
        snapshotFile(recordingId).takeIf(File::isFile)?.readBytes()

    private suspend fun buildSnapshot(recording: RecordingEntity, revision: Long) = AnnotationSnapshot(
        recordingId = recording.id,
        recordingName = recording.displayName,
        recordingSizeBytes = recording.sizeBytes,
        recordingDurationMs = recording.durationMs,
        revision = revision,
        comments = dao.getComments(recording.id).map {
            SidecarComment(it.id, it.timestampMs, it.text, it.createdAt, it.updatedAt)
        },
        redactions = dao.getRedactions(recording.id).map {
            SidecarRedaction(it.id, it.startMs, it.endMs, it.text, it.createdAt)
        },
        speakerAliases = dao.getAliases(recording.id).associate { it.speakerId to it.displayName },
        sets = dao.getConversationSets(recording.id).map {
            SidecarSet(it.id, it.orderIndex, it.startMs, it.endMs, it.title, it.summary, it.speakerIds)
        },
    )

    private fun writeEncryptedAtomically(snapshot: AnnotationSnapshot) {
        val target = snapshotFile(snapshot.recordingId)
        val previous = previousSnapshotFile(snapshot.recordingId)
        val temporary = File(directory, target.name + ".tmp")
        val plaintext = json.encodeToString(snapshot).encodeToByteArray()
        val encrypted = encrypt(plaintext)
        temporary.outputStream().use { output ->
            output.write(encrypted)
            output.flush()
            (output as java.io.FileOutputStream).fd.sync()
        }
        check(readSnapshotFile(temporary)?.recordingId == snapshot.recordingId) {
            "The new local marker backup could not be verified."
        }
        if (target.exists()) {
            Files.copy(target.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(readSnapshotFile(target)?.revision == snapshot.revision) {
            "The saved local marker backup could not be verified."
        }
    }

    private fun readSnapshot(recordingId: String): AnnotationSnapshot? =
        readSnapshotFile(snapshotFile(recordingId)) ?: readSnapshotFile(previousSnapshotFile(recordingId))

    private fun readSnapshotFile(file: File): AnnotationSnapshot? = runCatching {
        if (!file.isFile) return@runCatching null
        json.decodeFromString<AnnotationSnapshot>(decrypt(file.readBytes()).decodeToString())
    }.getOrNull()

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(backupKey(), "AES"), GCMParameterSpec(128, iv))
        return FILE_MAGIC + iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        check(payload.size > FILE_MAGIC.size + 12 && payload.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            "Unsupported marker backup format."
        }
        val iv = payload.copyOfRange(FILE_MAGIC.size, FILE_MAGIC.size + 12)
        val ciphertext = payload.copyOfRange(FILE_MAGIC.size + 12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(backupKey(), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun backupKey(): ByteArray {
        val existing = secrets.get(KEY_SECRET_NAME)
        if (!existing.isNullOrBlank()) return Base64.decode(existing, Base64.NO_WRAP)
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        secrets.put(KEY_SECRET_NAME, Base64.encodeToString(generated, Base64.NO_WRAP))
        return generated
    }

    private fun updateStatus(recordingId: String, status: AnnotationBackupStatus): AnnotationBackupStatus {
        val encoded = listOf(
            status.protectedItemCount,
            if (status.localCurrent) 1 else 0,
            if (status.folderCurrent) 1 else 0,
            status.localRevision,
            status.folderRevision,
            encodeStatusText(status.localError),
            encodeStatusText(status.folderError),
        ).joinToString("|")
        preferences.edit().putString(statusKey(recordingId), encoded).commit()
        stateFlows.getOrPut(recordingId) { MutableStateFlow(status) }.value = status
        return status
    }

    private fun loadStatus(recordingId: String): AnnotationBackupStatus {
        val parts = preferences.getString(statusKey(recordingId), null)?.split('|') ?: return AnnotationBackupStatus()
        if (parts.size < 7) return AnnotationBackupStatus()
        return AnnotationBackupStatus(
            protectedItemCount = parts[0].toIntOrNull() ?: 0,
            localCurrent = parts[1] == "1",
            folderCurrent = parts[2] == "1",
            localRevision = parts[3].toLongOrNull() ?: 0,
            folderRevision = parts[4].toLongOrNull() ?: 0,
            localError = decodeStatusText(parts[5]),
            folderError = decodeStatusText(parts[6]),
        )
    }

    private fun encodeStatusText(value: String?): String =
        value?.let { Base64.encodeToString(it.encodeToByteArray(), Base64.NO_WRAP) }.orEmpty()

    private fun decodeStatusText(value: String): String? =
        value.takeIf(String::isNotBlank)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP).decodeToString() }.getOrNull()
        }

    private fun snapshotFile(recordingId: String) = File(directory, "${sha256(recordingId)}.annotations")
    private fun previousSnapshotFile(recordingId: String) = File(directory, "${sha256(recordingId)}.annotations.previous")
    private fun statusKey(recordingId: String) = sha256(recordingId)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val FILE_MAGIC = "DBAK1".encodeToByteArray()
        const val KEY_SECRET_NAME = "annotation_backup_key"
    }
}
