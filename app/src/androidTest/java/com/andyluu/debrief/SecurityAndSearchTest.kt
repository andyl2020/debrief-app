package com.andyluu.debrief

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andyluu.debrief.data.CommentEntity
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
}
