package com.andyluu.debrief.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DebriefDao {
    @Query("SELECT * FROM recordings ORDER BY lastModified DESC, displayName COLLATE NOCASE")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeRecording(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE documentUri = :uri LIMIT 1")
    suspend fun getRecordingByUri(uri: String): RecordingEntity?

    @Upsert
    suspend fun upsertRecording(recording: RecordingEntity)

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("UPDATE recordings SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: RecordingStatus, error: String? = null)

    @Query("UPDATE recordings SET playbackPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long)

    @Query("DELETE FROM recordings WHERE id NOT IN (:activeIds)")
    suspend fun deleteMissingRecordings(activeIds: List<String>)

    @Query("DELETE FROM recordings")
    suspend fun deleteAllRecordings()

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun getSegments(recordingId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_words WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun getWords(recordingId: String): List<TranscriptWordEntity>

    @Query("SELECT * FROM comments WHERE recordingId = :recordingId ORDER BY timestampMs")
    fun observeComments(recordingId: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE recordingId = :recordingId ORDER BY timestampMs")
    suspend fun getComments(recordingId: String): List<CommentEntity>

    @Query("SELECT * FROM speaker_aliases WHERE recordingId = :recordingId")
    fun observeAliases(recordingId: String): Flow<List<SpeakerAliasEntity>>

    @Query("SELECT * FROM speaker_aliases WHERE recordingId = :recordingId")
    suspend fun getAliases(recordingId: String): List<SpeakerAliasEntity>

    @Query("SELECT * FROM comments WHERE id = :commentId")
    suspend fun getComment(commentId: String): CommentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComment(comment: CommentEntity)

    @Query("DELETE FROM comments WHERE id = :commentId")
    suspend fun deleteComment(commentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(alias: SpeakerAliasEntity)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteSegments(recordingId: String)

    @Query("DELETE FROM transcript_words WHERE recordingId = :recordingId")
    suspend fun deleteWords(recordingId: String)

    @Insert
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Insert
    suspend fun insertWords(words: List<TranscriptWordEntity>)

    @Transaction
    suspend fun replaceTranscript(
        recordingId: String,
        segments: List<TranscriptSegmentEntity>,
        words: List<TranscriptWordEntity>,
    ) {
        deleteSegments(recordingId)
        deleteWords(recordingId)
        insertSegments(segments)
        insertWords(words)
    }
}
