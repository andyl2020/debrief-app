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

    @Query("UPDATE recordings SET documentUri = :documentUri, displayName = :displayName, lastModified = :lastModified WHERE id = :id")
    suspend fun updateRecordingLocation(id: String, documentUri: String, displayName: String, lastModified: Long)

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

    @Query("SELECT * FROM suspect_spans WHERE recordingId = :recordingId ORDER BY startMs")
    fun observeSuspectSpans(recordingId: String): Flow<List<SuspectSpanEntity>>

    @Query("SELECT * FROM suspect_spans WHERE recordingId = :recordingId ORDER BY score DESC, startMs")
    suspend fun getSuspectSpans(recordingId: String): List<SuspectSpanEntity>

    @Query("DELETE FROM suspect_spans WHERE recordingId = :recordingId")
    suspend fun deleteSuspectSpans(recordingId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuspectSpans(spans: List<SuspectSpanEntity>)

    @Query("UPDATE suspect_spans SET resolved = 1 WHERE recordingId = :recordingId AND startMs <= :endMs AND endMs >= :startMs")
    suspend fun markSuspectSpansResolved(recordingId: String, startMs: Long, endMs: Long)

    @Query("SELECT * FROM repair_runs WHERE recordingId = :recordingId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestRepairRun(recordingId: String): Flow<RepairRunEntity?>

    @Query("SELECT * FROM repair_runs WHERE recordingId = :recordingId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestRepairRun(recordingId: String): RepairRunEntity?

    @Query("SELECT * FROM repair_runs WHERE id = :runId")
    suspend fun getRepairRun(runId: String): RepairRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRepairRun(run: RepairRunEntity)

    @Query("SELECT * FROM repairs WHERE recordingId = :recordingId ORDER BY startMs")
    fun observeRepairs(recordingId: String): Flow<List<RepairEntity>>

    @Query("SELECT * FROM repairs WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun getRepairs(recordingId: String): List<RepairEntity>

    @Query("SELECT * FROM repairs WHERE id = :repairId")
    suspend fun getRepair(repairId: String): RepairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRepair(repair: RepairEntity)

    @Query("UPDATE repairs SET reverted = :reverted, applied = CASE WHEN :reverted THEN 0 ELSE 1 END WHERE id = :repairId")
    suspend fun setRepairReverted(repairId: String, reverted: Boolean)

    @Query("SELECT * FROM comments WHERE recordingId = :recordingId ORDER BY timestampMs")
    fun observeComments(recordingId: String): Flow<List<CommentEntity>>

    @Query("SELECT * FROM comments WHERE recordingId = :recordingId ORDER BY timestampMs")
    suspend fun getComments(recordingId: String): List<CommentEntity>

    @Query("SELECT * FROM speaker_aliases WHERE recordingId = :recordingId")
    fun observeAliases(recordingId: String): Flow<List<SpeakerAliasEntity>>

    @Query("SELECT * FROM speaker_aliases WHERE recordingId = :recordingId")
    suspend fun getAliases(recordingId: String): List<SpeakerAliasEntity>

    @Query("SELECT * FROM ai_recordings")
    fun observeAllAiRecordings(): Flow<List<AiRecordingEntity>>

    @Query("SELECT * FROM ai_recordings WHERE recordingId = :recordingId")
    fun observeAiRecording(recordingId: String): Flow<AiRecordingEntity?>

    @Query("SELECT * FROM ai_recordings WHERE recordingId = :recordingId")
    suspend fun getAiRecording(recordingId: String): AiRecordingEntity?

    @Upsert
    suspend fun upsertAiRecording(aiRecording: AiRecordingEntity)

    @Query("UPDATE ai_recordings SET skipAiPass = :skip, status = CASE WHEN :skip THEN 'SKIPPED' ELSE status END WHERE recordingId = :recordingId")
    suspend fun updateAiSkip(recordingId: String, skip: Boolean)

    @Query("SELECT * FROM conversation_sets ORDER BY recordingId, orderIndex")
    fun observeAllConversationSets(): Flow<List<ConversationSetEntity>>

    @Query("SELECT * FROM conversation_sets WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeConversationSets(recordingId: String): Flow<List<ConversationSetEntity>>

    @Query("SELECT * FROM conversation_sets WHERE recordingId = :recordingId ORDER BY orderIndex")
    suspend fun getConversationSets(recordingId: String): List<ConversationSetEntity>

    @Upsert
    suspend fun upsertConversationSet(set: ConversationSetEntity)

    @Query("DELETE FROM conversation_sets WHERE id = :setId")
    suspend fun deleteConversationSet(setId: String)

    @Query("DELETE FROM conversation_sets WHERE recordingId = :recordingId")
    suspend fun deleteConversationSets(recordingId: String)

    @Insert
    suspend fun insertConversationSets(sets: List<ConversationSetEntity>)

    @Query("SELECT * FROM speaker_suggestions WHERE recordingId = :recordingId ORDER BY speakerId")
    fun observeSpeakerSuggestions(recordingId: String): Flow<List<SpeakerSuggestionEntity>>

    @Query("SELECT * FROM speaker_suggestions WHERE recordingId = :recordingId ORDER BY speakerId")
    suspend fun getSpeakerSuggestions(recordingId: String): List<SpeakerSuggestionEntity>

    @Query("DELETE FROM speaker_suggestions WHERE recordingId = :recordingId")
    suspend fun deleteSpeakerSuggestions(recordingId: String)

    @Query("DELETE FROM speaker_suggestions WHERE recordingId = :recordingId AND speakerId = :speakerId")
    suspend fun deleteSpeakerSuggestion(recordingId: String, speakerId: String)

    @Insert
    suspend fun insertSpeakerSuggestions(suggestions: List<SpeakerSuggestionEntity>)

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

    @Transaction
    suspend fun replaceSuspectSpans(recordingId: String, spans: List<SuspectSpanEntity>) {
        deleteSuspectSpans(recordingId)
        if (spans.isNotEmpty()) insertSuspectSpans(spans)
    }

    @Transaction
    suspend fun replaceAiAnalysis(
        aiRecording: AiRecordingEntity,
        sets: List<ConversationSetEntity>,
        suggestions: List<SpeakerSuggestionEntity>,
    ) {
        upsertAiRecording(aiRecording)
        deleteConversationSets(aiRecording.recordingId)
        deleteSpeakerSuggestions(aiRecording.recordingId)
        if (sets.isNotEmpty()) insertConversationSets(sets)
        if (suggestions.isNotEmpty()) insertSpeakerSuggestions(suggestions)
    }
}
