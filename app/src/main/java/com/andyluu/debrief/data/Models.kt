package com.andyluu.debrief.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class RecordingStatus { NEW, QUEUED, TRANSCRIBING, READY, FAILED }
enum class AiPassStatus { NOT_RUN, RUNNING, READY, FAILED, SKIPPED }
enum class RepairRunStatus { QUEUED, RUNNING, READY, FAILED, PARTIAL }
enum class RepairRunMode { AUTO, SELECTION }
enum class TranscriptQualityStatus { GOOD, CHECK, ISSUE }

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long = 0,
    val status: RecordingStatus = RecordingStatus.NEW,
    val errorMessage: String? = null,
    val playbackPositionMs: Long = 0,
    val discoveredAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "startMs"])],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Entity(
    tableName = "transcript_words",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "startMs"])],
)
data class TranscriptWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Double? = null,
)

@Entity(
    tableName = "comments",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "timestampMs"])],
)
data class CommentEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val timestampMs: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "speaker_aliases", primaryKeys = ["recordingId", "speakerId"])
data class SpeakerAliasEntity(
    val recordingId: String,
    val speakerId: String,
    val displayName: String,
)

@Entity(
    tableName = "ai_recordings",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class AiRecordingEntity(
    @PrimaryKey val recordingId: String,
    val summary: String = "",
    val originalDisplayName: String? = null,
    val suggestedFilename: String? = null,
    val skipAiPass: Boolean = false,
    val status: AiPassStatus = AiPassStatus.NOT_RUN,
    val errorMessage: String? = null,
    val provider: String? = null,
    val lastRunAt: Long? = null,
)

@Entity(
    tableName = "conversation_sets",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "orderIndex"])],
)
data class ConversationSetEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val summary: String = "",
    val speakerIds: String = "",
)

@Entity(
    tableName = "speaker_suggestions",
    primaryKeys = ["recordingId", "speakerId"],
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class SpeakerSuggestionEntity(
    val recordingId: String,
    val speakerId: String,
    val suggestedName: String,
    val confidence: String,
    val evidence: String = "",
)

@Entity(
    tableName = "suspect_spans",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "startMs"])],
)
data class SuspectSpanEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val flaggedWordCount: Int,
    val minConfidence: Double,
    val meanConfidence: Double,
    val score: Double,
    val resolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "repair_runs",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId"), Index(value = ["recordingId", "createdAt"])],
)
data class RepairRunEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val mode: RepairRunMode,
    val status: RepairRunStatus = RepairRunStatus.QUEUED,
    val provider: String = "gemini",
    val stageLabel: String = "Queued",
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val selectionStartMs: Long? = null,
    val selectionEndMs: Long? = null,
    val fixedCount: Int = 0,
    val inaudibleCount: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "repairs",
    foreignKeys = [
        ForeignKey(
            entity = RepairRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId"), Index("recordingId"), Index(value = ["recordingId", "startMs"])],
)
data class RepairEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val recordingId: String,
    val startMs: Long,
    val endMs: Long,
    val original: String,
    val repaired: String?,
    val type: String,
    val source: String,
    val confidence: String,
    val reason: String = "",
    val applied: Boolean = true,
    val reverted: Boolean = false,
    val clipUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transcript_quality_reports",
    foreignKeys = [ForeignKey(
        entity = RecordingEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class TranscriptQualityReportEntity(
    @PrimaryKey val recordingId: String,
    val status: TranscriptQualityStatus,
    val provider: String,
    val uploadMode: String,
    val audioDurationMs: Long,
    val transcriptStartMs: Long?,
    val transcriptEndMs: Long?,
    val segmentCount: Int,
    val wordCount: Int,
    val speakerCount: Int,
    val wordsPerMinute: Double,
    val warningCount: Int,
    val warningsText: String = "",
    val recommendation: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class ReviewBundle(
    val recording: RecordingEntity,
    val segments: List<TranscriptSegmentEntity>,
    val words: List<TranscriptWordEntity>,
    val comments: List<CommentEntity>,
    val aliases: List<SpeakerAliasEntity>,
)

data class SearchHit(
    val recordingId: String,
    val recordingName: String,
    val timestampMs: Long,
    val speakerId: String?,
    val snippet: String,
    val isComment: Boolean,
)

@Serializable
data class SidecarDocument(
    val schemaVersion: Int = 2,
    val recordingName: String,
    val recordingSizeBytes: Long,
    val transcript: List<SidecarSegment>,
    val words: List<SidecarWord>,
    val comments: List<SidecarComment>,
    val speakerAliases: Map<String, String>,
    val originalRecordingName: String? = null,
    val aiSummary: String = "",
    val skipAiPass: Boolean = false,
    val sets: List<SidecarSet> = emptyList(),
    val speakerSuggestions: List<SidecarSpeakerSuggestion> = emptyList(),
)

@Serializable
data class SidecarSegment(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class SidecarWord(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class SidecarComment(
    val id: String,
    val timestampMs: Long,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class SidecarSet(
    val id: String,
    val orderIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val summary: String,
    val speakerIds: String,
)

@Serializable
data class SidecarSpeakerSuggestion(
    val speakerId: String,
    val suggestedName: String,
    val confidence: String,
    val evidence: String,
)
