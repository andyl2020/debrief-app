package com.andyluu.debrief.share

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudShareClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun draftWirePayloadIncludesDefaultAudioMimeType() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"draftId":"draft-1","expiresAfterHours":24,"status":"DRAFT","sets":[]}"""),
        )

        val response = CloudShareClient().createDraft(
            baseUrl = server.url("/").newBuilder().host("localhost").build().toString(),
            ownerToken = "owner-token",
            request = CreateCloudDraftRequest(
                title = "Private share",
                expiryDays = 30,
                sets = listOf(CreateCloudSetRequest("set-1", "Set 1", 5_000)),
            ),
        )

        assertEquals("draft-1", response.draftId)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"audioMimeType\":\"audio/mp4\""))
        assertEquals("Bearer owner-token", request.getHeader("Authorization"))
    }
}
