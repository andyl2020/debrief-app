package com.andyluu.debrief.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class RecordingStatus { NEW, QUEUED, TRANSCRIBING, READY, FAILED }

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
    val schemaVersion: Int = 1,
    val recordingName: String,
    val recordingSizeBytes: Long,
    val transcript: List<SidecarSegment>,
    val words: List<SidecarWord>,
    val comments: List<SidecarComment>,
    val speakerAliases: Map<String, String>,
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
