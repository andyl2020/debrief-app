package com.andyluu.debrief.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiAiProviderTest {
    @Test
    fun sendsStructuredSchemaAndParsesCompleteResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(geminiEnvelope(resultJson())))
            val provider = GeminiAiProvider(baseUrl = server.url("/v1beta/models").toString())

            val result = provider.analyze(AiPassRequest("Analyze this transcript"), "test-key")

            assertEquals("A concise summary", result.recordingSummary)
            assertEquals("Opening", result.sets.single().title)
            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.path)
            assertEquals("test-key", request.getHeader("x-goog-api-key"))
            val body = aiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(
                "application/json",
                body["generationConfig"]?.jsonObject?.get("responseMimeType").toString().trim('"'),
            )
            assertNotNull(body["generationConfig"]?.jsonObject?.get("responseJsonSchema"))
        }
    }

    @Test
    fun quotaResponseFallsBackToFlashLite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(geminiEnvelope(resultJson())))
            val provider = GeminiAiProvider(baseUrl = server.url("/v1beta/models").toString())

            provider.analyze(AiPassRequest("Analyze"), "test-key")

            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", server.takeRequest().path)
            assertEquals("/v1beta/models/gemini-2.5-flash-lite:generateContent", server.takeRequest().path)
        }
    }

    @Test
    fun exhaustedQuotaIsMarkedForWorkerRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"primary quota"}}"""))
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"fallback quota"}}"""))
            val provider = GeminiAiProvider(baseUrl = server.url("/v1beta/models").toString())

            try {
                provider.analyze(AiPassRequest("Analyze"), "test-key")
                fail("Expected a retryable quota exception")
            } catch (error: AiPassException) {
                assertTrue(error.retryable)
            }
        }
    }

    private fun resultJson() = aiJson.encodeToString(
        AiPassResponse.serializer(),
        AiPassResponse(
            recordingSummary = "A concise summary",
            suggestedFilename = "2026-07-06 - 1 set - opening",
            sets = listOf(AiSetResult(0, 60_000, "Opening", "Introductions.", listOf("Speaker A"))),
            speakers = listOf(AiSpeakerResult("Speaker A", "Andy", "explicit", "My name is Andy")),
            andySpeakerId = "Speaker A",
        ),
    )

    private fun geminiEnvelope(text: String): String = aiJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(text)) }) })
                    })
                })
            })
        },
    )
}
