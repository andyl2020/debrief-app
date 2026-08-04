package com.andyluu.debrief.share

import android.content.Context
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.ShareDraftEntity
import com.andyluu.debrief.data.ShareDraftStatus
import com.andyluu.debrief.data.SharePartEntity
import com.andyluu.debrief.data.SharePartStatus
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import com.andyluu.debrief.ui.REDACTION_LABEL
import com.andyluu.debrief.ui.redactedTranscriptText
import com.andyluu.debrief.ui.redactionMuteRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ShareSelectionPreview(
    val recordingId: String,
    val recordingName: String,
    val sets: List<ShareSetPreview>,
    val totalDurationMs: Long,
    val commentCount: Int,
    val redactionCount: Int,
)

data class ShareSetPreview(
    val id: String,
    val title: String,
    val durationMs: Long,
    val transcriptSegmentCount: Int,
    val commentCount: Int,
    val redactionCount: Int,
)

class ShareSnapshotBuilder(
    context: Context,
    database: DebriefDatabase,
) {
    private val appContext = context.applicationContext
    private val dao = database.dao()
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    suspend fun preview(recordingId: String, selectedSetIds: Collection<String>): ShareSelectionPreview =
        withContext(Dispatchers.IO) {
            val source = loadSource(recordingId, selectedSetIds)
            ShareSelectionPreview(
                recordingId = recordingId,
                recordingName = source.recordingName,
                sets = source.sets.map { set ->
                    ShareSetPreview(
                        id = set.id,
                        title = displayTitle(set),
                        durationMs = set.endMs - set.startMs,
                        transcriptSegmentCount = source.segments.count { it.startMs < set.endMs && it.endMs > set.startMs },
                        commentCount = source.comments.count { it.timestampMs in set.startMs..set.endMs },
                        redactionCount = source.redactions.count { it.startMs < set.endMs && it.endMs > set.startMs },
                    )
                },
                totalDurationMs = source.sets.sumOf { it.endMs - it.startMs },
                commentCount = source.sets.sumOf { set -> source.comments.count { it.timestampMs in set.startMs..set.endMs } },
                redactionCount = source.sets.sumOf { set -> source.redactions.count { it.startMs < set.endMs && it.endMs > set.startMs } },
            )
        }

    suspend fun createDraft(
        recordingId: String,
        selectedSetIds: Collection<String>,
        title: String,
        expiryDays: Int,
        pinEnabled: Boolean,
    ): String = withContext(Dispatchers.IO) {
        require(expiryDays in setOf(30, 60, 90)) { "Expiry must be 30, 60, or 90 days." }
        val cleanTitle = title.trim().takeIf(String::isNotBlank)?.take(160)
            ?: throw IllegalArgumentException("Enter a share title.")
        val source = loadSource(recordingId, selectedSetIds)
        val draftId = UUID.randomUUID().toString()
        val directory = File(appContext.cacheDir, "share-drafts/$draftId")
        check(directory.mkdirs() || directory.isDirectory) { "Couldn't create private share workspace." }
        try {
            val aliases = source.aliases.associate { it.speakerId to it.displayName }
            val parts = source.sets.mapIndexed { position, set ->
                val setTitle = displayTitle(set)
                val duration = set.endMs - set.startMs
                val metadata = buildMetadata(set, setTitle, source.segments, source.words, source.comments, source.redactions, aliases)
                require(metadata.segments.isNotEmpty()) { "$setTitle has no transcript inside its boundaries." }
                val metadataFile = File(directory, "set_${position.toString().padStart(2, '0')}_metadata.json")
                metadataFile.writeText(json.encodeToString(metadata), Charsets.UTF_8)
                val muteRanges = redactionMuteRanges(source.redactions)
                    .mapNotNull { range ->
                        val start = maxOf(range.startMs, set.startMs)
                        val end = minOf(range.endMs, set.endMs)
                        if (end > start) ShareMuteRange(start - set.startMs, end - set.startMs) else null
                    }
                SharePartEntity(
                    id = UUID.randomUUID().toString(),
                    draftId = draftId,
                    setId = set.id,
                    position = position,
                    title = setTitle,
                    sourceStartMs = set.startMs,
                    sourceEndMs = set.endMs,
                    durationMs = duration,
                    metadataPath = metadataFile.absolutePath,
                    redactionRangesJson = json.encodeToString(muteRanges),
                    metadataSizeBytes = metadataFile.length(),
                    metadataSha256 = sha256(metadataFile),
                    status = SharePartStatus.SNAPSHOT_READY,
                )
            }
            val draft = ShareDraftEntity(
                id = draftId,
                recordingId = recordingId,
                title = cleanTitle,
                selectedSetIdsJson = json.encodeToString(source.sets.map(ConversationSetEntity::id)),
                expiryDays = expiryDays,
                pinEnabled = pinEnabled,
                status = ShareDraftStatus.READY_TO_UPLOAD,
                stageLabel = "Private snapshot ready",
                totalSteps = parts.size * 3 + 2,
                updatedAt = System.currentTimeMillis(),
            )
            dao.createShareDraft(draft, parts)
            draftId
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private suspend fun loadSource(recordingId: String, selectedSetIds: Collection<String>): SourceRows {
        val selectedIds = selectedSetIds.distinct()
        require(selectedIds.isNotEmpty()) { "Choose at least one completed set." }
        require(selectedIds.size <= SHARE_MAX_SETS) { "One link can contain at most $SHARE_MAX_SETS sets." }
        val recording = dao.getRecording(recordingId) ?: throw IllegalArgumentException("That recording is no longer available.")
        require(recording.status == RecordingStatus.READY) { "Transcribe this recording before sharing sets." }
        val allSets = dao.getConversationSets(recordingId)
        val byId = allSets.associateBy(ConversationSetEntity::id)
        val sets = selectedIds.map { id -> byId[id] ?: throw IllegalArgumentException("A selected set no longer exists.") }
            .sortedBy(ConversationSetEntity::startMs)
        require(sets.all { it.recordingId == recordingId }) { "All selected sets must come from one recording." }
        require(sets.all { it.endMs > it.startMs }) { "Finish every selected set by adding an end marker." }
        require(sets.sumOf { it.endMs - it.startMs } <= SHARE_MAX_DURATION_MS) { "One link can contain at most three hours of audio." }
        val segments = dao.getSegments(recordingId)
        require(segments.isNotEmpty()) { "The recording transcript is empty." }
        return SourceRows(
            recordingName = recording.displayName,
            sets = sets,
            segments = segments,
            words = dao.getWords(recordingId),
            comments = dao.getComments(recordingId),
            redactions = dao.getRedactions(recordingId),
            aliases = dao.getAliases(recordingId),
        )
    }

    private fun buildMetadata(
        set: ConversationSetEntity,
        setTitle: String,
        segments: List<TranscriptSegmentEntity>,
        words: List<TranscriptWordEntity>,
        comments: List<com.andyluu.debrief.data.CommentEntity>,
        redactions: List<com.andyluu.debrief.data.RedactionEntity>,
        aliases: Map<String, String>,
    ): ShareMetadataPayload {
        val relativeSegments = segments.mapNotNull { segment ->
            if (segment.startMs >= set.endMs || segment.endMs <= set.startMs) return@mapNotNull null
            val timedWords = words.filter { word ->
                word.speakerId == segment.speakerId &&
                    word.endMs > segment.startMs && word.startMs < segment.endMs &&
                    word.startMs >= set.startMs && word.endMs <= set.endMs
            }
            val text = if (timedWords.isNotEmpty()) {
                redactWords(timedWords, redactions)
            } else {
                if (segment.startMs < set.startMs || segment.endMs > set.endMs) return@mapNotNull null
                redactedTranscriptText(segment.text, emptyList(), redactions, segment.startMs, segment.endMs)
            }
            if (text.isBlank()) return@mapNotNull null
            val absoluteStart = timedWords.firstOrNull()?.startMs ?: maxOf(segment.startMs, set.startMs)
            val absoluteEnd = timedWords.lastOrNull()?.endMs ?: minOf(segment.endMs, set.endMs)
            if (absoluteEnd <= absoluteStart) return@mapNotNull null
            ShareTranscriptSegment(
                speaker = aliases[segment.speakerId]?.takeIf(String::isNotBlank) ?: segment.speakerId,
                startMs = absoluteStart - set.startMs,
                endMs = absoluteEnd - set.startMs,
                text = text,
            )
        }
        val relativeComments = comments
            .filter { it.timestampMs in set.startMs..set.endMs }
            .sortedBy { it.timestampMs }
            .map { ShareComment(it.timestampMs - set.startMs, it.text) }
        return ShareMetadataPayload(
            title = setTitle,
            durationMs = set.endMs - set.startMs,
            segments = relativeSegments,
            comments = relativeComments,
        )
    }

    private fun redactWords(
        words: List<TranscriptWordEntity>,
        redactions: List<com.andyluu.debrief.data.RedactionEntity>,
    ): String {
        val output = mutableListOf<String>()
        words.forEach { word ->
            val redacted = redactions.any { it.startMs < word.endMs && it.endMs > word.startMs }
            if (redacted) {
                if (output.lastOrNull() != REDACTION_LABEL) output += REDACTION_LABEL
            } else {
                output += word.text
            }
        }
        return output.joinToString(" ").trim()
    }

    private fun displayTitle(set: ConversationSetEntity): String =
        set.title.trim().takeIf(String::isNotBlank) ?: "Set ${set.orderIndex + 1}"

    private data class SourceRows(
        val recordingName: String,
        val sets: List<ConversationSetEntity>,
        val segments: List<TranscriptSegmentEntity>,
        val words: List<TranscriptWordEntity>,
        val comments: List<com.andyluu.debrief.data.CommentEntity>,
        val redactions: List<com.andyluu.debrief.data.RedactionEntity>,
        val aliases: List<com.andyluu.debrief.data.SpeakerAliasEntity>,
    )
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
