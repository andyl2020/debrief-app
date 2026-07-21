package com.andyluu.debrief.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SidecarStore(
    private val context: Context,
    database: DebriefDatabase,
    private val search: SearchRepository,
) {
    private val dao = database.dao()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun write(root: DocumentFile, recordingId: String) = withContext(Dispatchers.IO) {
        val recording = dao.getRecording(recordingId) ?: return@withContext
        val ai = dao.getAiRecording(recordingId)
        val document = SidecarDocument(
            recordingName = recording.displayName,
            recordingSizeBytes = recording.sizeBytes,
            transcript = dao.getSegments(recordingId).map {
                SidecarSegment(it.speakerId, it.startMs, it.endMs, it.text)
            },
            words = dao.getWords(recordingId).map {
                SidecarWord(it.speakerId, it.startMs, it.endMs, it.text)
            },
            comments = dao.getComments(recordingId).map {
                SidecarComment(it.id, it.timestampMs, it.text, it.createdAt, it.updatedAt)
            },
            redactions = dao.getRedactions(recordingId).map {
                SidecarRedaction(it.id, it.startMs, it.endMs, it.text, it.createdAt)
            },
            speakerAliases = dao.getAliases(recordingId).associate { it.speakerId to it.displayName },
            originalRecordingName = ai?.originalDisplayName,
            aiSummary = ai?.summary.orEmpty(),
            skipAiPass = ai?.skipAiPass ?: false,
            sets = dao.getConversationSets(recordingId).map {
                SidecarSet(it.id, it.orderIndex, it.startMs, it.endMs, it.title, it.summary, it.speakerIds)
            },
            speakerSuggestions = dao.getSpeakerSuggestions(recordingId).map {
                SidecarSpeakerSuggestion(it.speakerId, it.suggestedName, it.confidence, it.evidence)
            },
        )
        val directory = findContainingDirectory(root, recording.documentUri) ?: root
        val name = sidecarName(recording.displayName)
        val file = directory.findFile(name) ?: directory.createFile("application/json", name)
            ?: error("Could not create sidecar file")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use {
            it.write(json.encodeToString(document))
        } ?: error("Could not write sidecar file")
    }

    suspend fun restoreIfPresent(root: DocumentFile, recording: RecordingEntity) = withContext(Dispatchers.IO) {
        val directory = findContainingDirectory(root, recording.documentUri) ?: return@withContext
        val file = directory.findFile(sidecarName(recording.displayName)) ?: return@withContext
        val document = runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                json.decodeFromString<SidecarDocument>(reader.readText())
            }
        }.getOrNull() ?: return@withContext
        if (document.recordingSizeBytes != recording.sizeBytes) return@withContext

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
        document.speakerAliases.forEach { (id, name) -> dao.upsertAlias(SpeakerAliasEntity(recording.id, id, name)) }
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
            search.rebuild(recording.id)
        }
    }

    private fun sidecarName(recordingName: String) = "$recordingName.debrief.json"

    private fun findContainingDirectory(directory: DocumentFile, documentUri: String): DocumentFile? {
        directory.listFiles().forEach { file ->
            if (file.isFile && file.uri.toString() == documentUri) return directory
            if (file.isDirectory) findContainingDirectory(file, documentUri)?.let { return it }
        }
        return null
    }
}
