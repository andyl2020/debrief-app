package com.andyluu.debrief.share

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andyluu.debrief.DebriefApplication
import com.andyluu.debrief.data.CommentEntity
import com.andyluu.debrief.data.ConversationSetEntity
import com.andyluu.debrief.data.RecordingEntity
import com.andyluu.debrief.data.RecordingStatus
import com.andyluu.debrief.data.RedactionEntity
import com.andyluu.debrief.data.ShareDraftStatus
import com.andyluu.debrief.data.SharedLinkStatus
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareLiveIntegrationTest {
    @Test
    fun publishesPrivateSnapshotAndRevokesIt() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val pairingCode = arguments.getString("sharePairingCode").orEmpty()
        val audioPath = arguments.getString("shareAudioPath").orEmpty()
        val baseUrl = arguments.getString("shareBaseUrl").orEmpty()
            .ifBlank { DEFAULT_BASE_URL }
            .trimEnd('/')
        assumeTrue("Live share test requires a pairing code", pairingCode.isNotBlank())
        assumeTrue("Live share test requires app-private sample audio", audioPath.isNotBlank() && File(audioPath).isFile)

        val app = ApplicationProvider.getApplicationContext<DebriefApplication>()
        val services = app.services
        val dao = services.database.dao()
        val repository = services.shares
        val recordingId = "live-share-recording"
        val setId = "live-share-set"
        dao.deleteRecording(recordingId)
        val audio = File(audioPath)
        dao.upsertRecording(
            RecordingEntity(
                id = recordingId,
                documentUri = Uri.fromFile(audio).toString(),
                displayName = "Private live integration.m4a",
                mimeType = "audio/mp4",
                sizeBytes = audio.length(),
                lastModified = audio.lastModified(),
                durationMs = 268_000,
                status = RecordingStatus.READY,
            )
        )
        dao.insertSegments(
            listOf(TranscriptSegmentEntity(recordingId = recordingId, speakerId = "S1", startMs = 0, endMs = 7_000, text = "outside-before hello private friend outside-after"))
        )
        dao.insertWords(
            listOf(
                word(recordingId, "outside-before", 200, 500),
                word(recordingId, "hello", 1_100, 1_500),
                word(recordingId, "private", 1_600, 2_000),
                word(recordingId, "friend", 2_200, 2_600),
                word(recordingId, "outside-after", 6_100, 6_500),
            )
        )
        dao.upsertComment(CommentEntity("inside-comment", recordingId, 1_500, "Included coaching note"))
        dao.upsertComment(CommentEntity("end-comment", recordingId, 6_000, "Must stay private at end boundary"))
        dao.upsertRedaction(RedactionEntity("private-word", recordingId, 1_600, 2_000, "private"))
        dao.insertConversationSets(listOf(ConversationSetEntity(setId, recordingId, 0, 1_000, 6_000, "Selected conversation")))

        repository.pair(baseUrl, pairingCode)
        val draftId = repository.createAndEnqueue(recordingId, listOf(setId), "Live private share", 30, null)
        val outcome = withTimeout(180_000) {
            combine(repository.sharedLinks, repository.drafts) { links, drafts -> links to drafts }
                .first { (links, drafts) ->
                    links.any { it.recordingId == recordingId } ||
                        drafts.any { it.id == draftId && (it.status == ShareDraftStatus.FAILED || it.errorMessage != null) }
                }
        }
        val failed = outcome.second.firstOrNull { it.id == draftId && (it.status == ShareDraftStatus.FAILED || it.errorMessage != null) }
        check(failed == null) { "Share worker failed at ${failed?.stageLabel}: ${failed?.errorMessage}" }
        val link = outcome.first.first { it.recordingId == recordingId }
        assertEquals(SharedLinkStatus.ACTIVE, link.status)
        assertTrue(link.url.startsWith("$baseUrl/s/"))
        val token = link.url.substringAfterLast('/')
        val client = OkHttpClient()
        val body = client.newCall(Request.Builder().url("$baseUrl/v1/public/$token").build()).execute().use { response ->
            assertEquals(200, response.code)
            response.body!!.string()
        }
        assertTrue(body.contains("hello [redacted] friend"))
        assertTrue(body.contains("Included coaching note"))
        assertFalse(body.contains("outside-before"))
        assertFalse(body.contains("outside-after"))
        assertFalse(body.contains("Must stay private at end boundary"))
        assertFalse(body.contains("\"private\""))
        val setCloudId = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("Public set ID missing")
        client.newCall(
            Request.Builder()
                .url("$baseUrl/v1/public/$token/sets/$setCloudId/audio")
                .header("Range", "bytes=0-1023")
                .build()
        ).execute().use { response ->
            assertEquals(206, response.code)
            assertTrue(response.body!!.bytes().isNotEmpty())
        }

        repository.refresh()
        val usage = withTimeout(10_000) { repository.cloudUsage.first { it != null } }!!
        assertEquals(SHARE_STORAGE_REFERENCE_BYTES, usage.referenceBytes)
        assertTrue(usage.currentBytes > 0)
        repository.revoke(link.id)
        client.newCall(Request.Builder().url("$baseUrl/v1/public/$token").build()).execute().use { response ->
            assertEquals(404, response.code)
        }
        dao.deleteRecording(recordingId)
    }

    private fun word(recordingId: String, text: String, startMs: Long, endMs: Long) = TranscriptWordEntity(
        recordingId = recordingId,
        speakerId = "S1",
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

    companion object {
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:8787"
    }
}
