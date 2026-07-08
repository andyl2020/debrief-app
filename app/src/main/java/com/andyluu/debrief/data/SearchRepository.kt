package com.andyluu.debrief.data

import androidx.sqlite.db.SimpleSQLiteQuery

class SearchRepository(private val database: DebriefDatabase) {
    private val dao = database.dao()

    suspend fun rebuild(recordingId: String) {
        val recording = dao.getRecording(recordingId) ?: return
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM transcript_fts WHERE recording_id = ?", arrayOf(recordingId))
            dao.getSegments(recordingId).forEach { segment ->
                db.execSQL(
                    "INSERT INTO transcript_fts(recording_id, recording_name, speaker_id, timestamp_ms, body, kind) VALUES(?,?,?,?,?,?)",
                    arrayOf(recordingId, recording.displayName, segment.speakerId, segment.startMs, segment.text, "transcript"),
                )
            }
            dao.getComments(recordingId).forEach { comment ->
                db.execSQL(
                    "INSERT INTO transcript_fts(recording_id, recording_name, speaker_id, timestamp_ms, body, kind) VALUES(?,?,?,?,?,?)",
                    arrayOf(recordingId, recording.displayName, "", comment.timestampMs, comment.text, "comment"),
                )
            }
            dao.getAiRecording(recordingId)?.let { ai ->
                if (ai.summary.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO transcript_fts(recording_id, recording_name, speaker_id, timestamp_ms, body, kind) VALUES(?,?,?,?,?,?)",
                        arrayOf(recordingId, recording.displayName, "", 0L, ai.summary, "summary"),
                    )
                }
            }
            dao.getConversationSets(recordingId).forEach { set ->
                val body = listOf(set.title, set.summary).filter(String::isNotBlank).joinToString(". ")
                if (body.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO transcript_fts(recording_id, recording_name, speaker_id, timestamp_ms, body, kind) VALUES(?,?,?,?,?,?)",
                        arrayOf(recordingId, recording.displayName, "", set.startMs, body, "summary"),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun search(query: String, recordingId: String? = null): List<SearchHit> {
        val match = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" }
        if (match.isBlank()) return emptyList()
        val scoped = recordingId != null
        val matchExpression = if (scoped) "body : ($match)" else match
        val sql = buildString {
            append("SELECT recording_id, recording_name, CAST(timestamp_ms AS INTEGER), speaker_id, ")
            append("snippet(transcript_fts, 4, '[', ']', '…', 16), kind FROM transcript_fts ")
            append("WHERE transcript_fts MATCH ? ")
            if (scoped) append("AND recording_id = ? AND kind = 'transcript' ")
            append("ORDER BY rank LIMIT 100")
        }
        val args = if (scoped) arrayOf(matchExpression, recordingId!!) else arrayOf(matchExpression)
        val cursor = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args))
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        SearchHit(
                            recordingId = it.getString(0),
                            recordingName = it.getString(1),
                            timestampMs = it.getLong(2),
                            speakerId = it.getString(3).takeIf(String::isNotBlank),
                            snippet = it.getString(4),
                            isComment = it.getString(5) == "comment",
                        )
                    )
                }
            }
        }
    }
}
