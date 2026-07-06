package com.andyluu.debrief

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.AiPassStatus
import com.andyluu.debrief.data.AiRecordingEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.SearchRepository
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecurityAndSearchTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun secretRoundTripsWithoutPlaintextAtRest() {
        val marker = "test-secret-${System.nanoTime()}"
        val store = SecureSecretStore(context)
        store.put("instrumentation", marker)

        assertEquals(marker, store.get("instrumentation"))
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/encrypted_secrets.xml")
        assertTrue(prefsFile.exists())
        assertFalse(prefsFile.readText().contains(marker))
        store.remove("instrumentation")
    }

    @Test
    fun ftsFindsTranscriptAndComments() = runBlocking {
        val db = DebriefDatabase.get(context)
        val dao = db.dao()
        val recording = RecordingEntity(
            id = "fts-test",
            documentUri = "content://test/recording",
            displayName = "Field Session.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 100,
            lastModified = 1,
        )
        dao.upsertRecording(recording)
        dao.replaceTranscript(
            recording.id,
            listOf(TranscriptSegmentEntity(recordingId = recording.id, speakerId = "Speaker A", startMs = 1234, endMs = 3000, text = "The launch checklist is complete")),
            listOf(TranscriptWordEntity(recordingId = recording.id, speakerId = "Speaker A", startMs = 1234, endMs = 1400, text = "launch")),
        )
        dao.upsertComment(CommentEntity("comment-test", recording.id, 2200, "Follow up with Jordan"))
        val search = SearchRepository(db)
        search.rebuild(recording.id)

        assertEquals(1234, search.search("launch", recording.id).single().timestampMs)
        assertTrue(search.search("Jordan", recording.id).single().isComment)
    }

    @Test
    fun recordingRescanUpsertPreservesTranscriptAndComments() = runBlocking {
        val dao = DebriefDatabase.get(context).dao()
        val recording = RecordingEntity(
            id = "rescan-preserves-children",
            documentUri = "content://test/preserved-recording",
            displayName = "Original name.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 200,
            lastModified = 1,
        )
        dao.upsertRecording(recording)
        dao.replaceTranscript(
            recording.id,
            listOf(TranscriptSegmentEntity(recordingId = recording.id, speakerId = "Speaker A", startMs = 100, endMs = 500, text = "Keep this transcript")),
            listOf(TranscriptWordEntity(recordingId = recording.id, speakerId = "Speaker A", startMs = 100, endMs = 500, text = "Keep")),
        )
        dao.upsertComment(CommentEntity("preserved-comment", recording.id, 200, "Keep this comment"))

        dao.upsertRecording(recording.copy(displayName = "Renamed recording.mp3", lastModified = 2))

        assertEquals("Keep this transcript", dao.getSegments(recording.id).single().text)
        assertEquals("Keep this comment", dao.getComment("preserved-comment")?.text)
    }

    @Test
    fun aiSummariesAreSearchableAndSurviveRecordingUpsert() = runBlocking {
        val db = DebriefDatabase.get(context)
        val dao = db.dao()
        val recording = RecordingEntity(
            id = "ai-search-test",
            documentUri = "content://test/ai-search",
            displayName = "Conversation.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 300,
            lastModified = 1,
        )
        dao.upsertRecording(recording)
        dao.replaceAiAnalysis(
            AiRecordingEntity(recording.id, summary = "Discussed the rooftop launch plan", status = AiPassStatus.READY),
            listOf(
                ConversationSetEntity(
                    id = "ai-search-set",
                    recordingId = recording.id,
                    orderIndex = 0,
                    startMs = 5_000,
                    endMs = 60_000,
                    title = "Rooftop planning",
                    summary = "Jordan confirms the venue.",
                    speakerIds = "Speaker A|Speaker B",
                )
            ),
            emptyList(),
        )
        val search = SearchRepository(db)
        search.rebuild(recording.id)

        assertEquals(0, search.search("launch", recording.id).single().timestampMs)
        assertEquals(5_000, search.search("Jordan", recording.id).single().timestampMs)

        dao.upsertRecording(recording.copy(displayName = "Renamed.m4a", lastModified = 2))
        assertEquals("Discussed the rooftop launch plan", dao.getAiRecording(recording.id)?.summary)
        assertEquals("Rooftop planning", dao.getConversationSets(recording.id).single().title)
    }
}
