package com.andyluu.debrief.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

class SidecarStore(
    private val context: Context,
    database: DebriefDatabase,
    private val search: SearchRepository,
    val annotations: AnnotationBackupStore,
) {
    private val dao = database.dao()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val writeMutex = Mutex()

    /**
     * Checkpoints the encrypted app-private annotation snapshot first, then the
     * two recording-folder sidecar copies. A folder failure is reported through
     * the returned durable status and does not invalidate the database copy.
     */
    suspend fun checkpoint(root: DocumentFile?, recordingId: String): AnnotationBackupStatus =
        writeMutex.withLock {
            val local = annotations.checkpoint(recordingId)
            if (!local.localCurrent) return@withLock local
            if (root == null) {
                return@withLock annotations.recordFolderResult(
                    recordingId,
                    local.localRevision,
                    success = false,
                    error = IllegalStateException("The recordings folder is not linked."),
                )
            }
            runCatching { writeExternalDocument(root, recordingId) }
                .fold(
                    onSuccess = {
                        annotations.recordFolderResult(recordingId, local.localRevision, success = true)
                    },
                    onFailure = { error ->
                        annotations.recordFolderResult(recordingId, local.localRevision, success = false, error = error)
                    },
                )
        }

    suspend fun write(root: DocumentFile, recordingId: String) {
        val status = checkpoint(root, recordingId)
        check(status.localCurrent) { status.localError ?: "Could not update the local marker backup." }
        check(status.folderRevision == status.localRevision) {
            status.folderError ?: "Could not update the recording-folder sidecar."
        }
    }

    suspend fun writeAfterRename(
        root: DocumentFile,
        recordingId: String,
        previousRecordingName: String,
    ) {
        write(root, recordingId)
        withContext(Dispatchers.IO) {
            val recording = dao.getRecording(recordingId) ?: return@withContext
            if (recording.displayName == previousRecordingName) return@withContext
            val directory = findContainingDirectory(root, recording.documentUri) ?: return@withContext
            directory.findFile(sidecarName(previousRecordingName))?.delete()
            directory.findFile(backupSidecarName(previousRecordingName))?.delete()
        }
    }

    suspend fun restoreIfPresent(root: DocumentFile, recording: RecordingEntity): Boolean =
        writeMutex.withLock { withContext(Dispatchers.IO) {
            val directory = findContainingDirectory(root, recording.documentUri)
            val candidates = listOfNotNull(
                directory?.findFile(sidecarName(recording.displayName)),
                directory?.findFile(backupSidecarName(recording.displayName)),
            )
            val document = candidates.firstNotNullOfOrNull { file ->
                readAndValidate(file, recording)
            }
            if (document == null) {
                val restored = annotations.restoreIfEmpty(recording)
                if (restored) search.rebuild(recording.id)
                return@withContext restored
            }

            restoreDocument(recording, document)
            val local = annotations.checkpoint(recording.id)
            if (local.localCurrent) {
                annotations.recordFolderResult(recording.id, local.localRevision, success = true)
            }

            // Recreate both copies after a successful fallback read. This also
            // upgrades older sidecar schemas without changing the raw transcript.
            runCatching { writeExternalDocument(root, recording.id) }
            true
        } }

    private suspend fun writeExternalDocument(root: DocumentFile, recordingId: String) =
        withContext(Dispatchers.IO) {
            val recording = dao.getRecording(recordingId) ?: error("Recording is no longer available.")
            val document = buildDocument(recording)
            val encoded = json.encodeToString(document)
            val directory = findContainingDirectory(root, recording.documentUri)
                ?: error("The recording is outside the linked folder.")

            // The backup copy is written and verified first. If Android revokes
            // access or a provider truncates the primary write, one current copy
            // still remains available for recovery.
            val backupResult = runCatching {
                writeAndVerify(directory, backupSidecarName(recording.displayName), encoded, recording)
            }
            val primaryResult = runCatching {
                writeAndVerify(directory, sidecarName(recording.displayName), encoded, recording)
            }
            if (backupResult.isFailure && primaryResult.isFailure) {
                throw primaryResult.exceptionOrNull()
                    ?: backupResult.exceptionOrNull()
                    ?: IllegalStateException("Could not update either sidecar copy.")
            }
        }

    private suspend fun buildDocument(recording: RecordingEntity): SidecarDocument {
        val ai = dao.getAiRecording(recording.id)
        return SidecarDocument(
            recordingId = recording.id,
            recordingName = recording.displayName,
            recordingSizeBytes = recording.sizeBytes,
            recordingDurationMs = recording.durationMs,
            writtenAtEpochMs = System.currentTimeMillis(),
            transcript = dao.getSegments(recording.id).map {
                SidecarSegment(it.speakerId, it.startMs, it.endMs, it.text)
            },
            words = dao.getWords(recording.id).map {
                SidecarWord(it.speakerId, it.startMs, it.endMs, it.text, it.confidence)
            },
            comments = dao.getComments(recording.id).map {
                SidecarComment(it.id, it.timestampMs, it.text, it.createdAt, it.updatedAt)
            },
            redactions = dao.getRedactions(recording.id).map {
                SidecarRedaction(it.id, it.startMs, it.endMs, it.text, it.createdAt)
            },
            speakerAliases = dao.getAliases(recording.id).associate { it.speakerId to it.displayName },
            originalRecordingName = ai?.originalDisplayName,
            aiSummary = ai?.summary.orEmpty(),
            skipAiPass = ai?.skipAiPass ?: false,
            sets = dao.getConversationSets(recording.id).map {
                SidecarSet(it.id, it.orderIndex, it.startMs, it.endMs, it.title, it.summary, it.speakerIds)
            },
            speakerSuggestions = dao.getSpeakerSuggestions(recording.id).map {
                SidecarSpeakerSuggestion(it.speakerId, it.suggestedName, it.confidence, it.evidence)
            },
        )
    }

    private suspend fun restoreDocument(recording: RecordingEntity, document: SidecarDocument) {
        dao.replaceTranscript(
            recording.id,
            document.transcript.map {
                TranscriptSegmentEntity(
                    recordingId = recording.id,
                    speakerId = it.speakerId,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    text = it.text,
                )
            },
            document.words.map {
                TranscriptWordEntity(
                    recordingId = recording.id,
                    speakerId = it.speakerId,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    text = it.text,
                    confidence = it.confidence,
                )
            },
        )
        document.comments.forEach {
            dao.upsertComment(CommentEntity(it.id, recording.id, it.timestampMs, it.text, it.createdAt, it.updatedAt))
        }
        if (document.redactions.isNotEmpty()) {
            dao.insertRedactions(document.redactions.map {
                RedactionEntity(it.id, recording.id, it.startMs, it.endMs, it.text, it.createdAt)
            })
        }
        document.speakerAliases.forEach { (id, name) ->
            dao.upsertAlias(SpeakerAliasEntity(recording.id, id, name))
        }
        if (document.aiSummary.isNotBlank() || document.sets.isNotEmpty() || document.skipAiPass) {
            dao.replaceAiAnalysis(
                AiRecordingEntity(
                    recordingId = recording.id,
                    summary = document.aiSummary,
                    originalDisplayName = document.originalRecordingName,
                    skipAiPass = document.skipAiPass,
                    status = if (document.skipAiPass) AiPassStatus.SKIPPED else AiPassStatus.READY,
                ),
                document.sets.map {
                    ConversationSetEntity(it.id, recording.id, it.orderIndex, it.startMs, it.endMs, it.title, it.summary, it.speakerIds)
                },
                document.speakerSuggestions.map {
                    SpeakerSuggestionEntity(recording.id, it.speakerId, it.suggestedName, it.confidence, it.evidence)
                },
            )
        }
        if (document.transcript.isNotEmpty()) {
            dao.updateStatus(recording.id, RecordingStatus.READY)
        }
        search.rebuild(recording.id)
    }

    private fun writeAndVerify(
        directory: DocumentFile,
        name: String,
        encoded: String,
        recording: RecordingEntity,
    ) {
        val file = directory.findFile(name) ?: directory.createFile("application/json", name)
            ?: error("Could not create $name")
        context.contentResolver.openOutputStream(file.uri, "rwt")?.bufferedWriter()?.use {
            it.write(encoded)
            it.flush()
        } ?: error("Could not write $name")
        check(readAndValidate(file, recording) != null) { "Could not verify $name after writing it." }
    }

    private fun readAndValidate(file: DocumentFile, recording: RecordingEntity): SidecarDocument? =
        runCatching {
            val document = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                json.decodeFromString<SidecarDocument>(reader.readText())
            } ?: return@runCatching null
            if (document.recordingSizeBytes != recording.sizeBytes) return@runCatching null
            if (document.recordingDurationMs > 0 && recording.durationMs > 0 &&
                abs(document.recordingDurationMs - recording.durationMs) > 2_000
            ) return@runCatching null
            document
        }.getOrNull()

    private fun sidecarName(recordingName: String) = "$recordingName.debrief.json"
    private fun backupSidecarName(recordingName: String) = "$recordingName.debrief.backup.json"

    private fun findContainingDirectory(directory: DocumentFile, documentUri: String): DocumentFile? {
        directory.listFiles().forEach { file ->
            if (file.isFile && file.uri.toString() == documentUri) return directory
            if (file.isDirectory) findContainingDirectory(file, documentUri)?.let { return it }
        }
        return null
    }
}
