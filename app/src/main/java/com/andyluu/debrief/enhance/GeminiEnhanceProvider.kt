package com.andyluu.debrief.enhance

import android.util.Base64
import com.andyluu.debrief.ai.aiJson
import com.andyluu.debrief.ai.stripJsonFence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val enhanceJsonMediaType = "application/json".toMediaType()

class GeminiEnhanceProvider(
    private val model: String = "gemini-2.5-flash",
    private val fallbackModel: String = "gemini-2.5-flash-lite",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/models",
) {
    suspend fun repairText(prompt: String, apiKey: String): EnhanceTextResponse = withContext(Dispatchers.IO) {
        val payload = callWithFallback(prompt, emptyList(), enhanceTextSchema, apiKey)
        aiJson.decodeFromString(stripJsonFence(extractText(payload)))
    }

    suspend fun relisten(prompt: String, clips: List<EnhanceClip>, apiKey: String): EnhanceAudioResponse = withContext(Dispatchers.IO) {
        val payload = callWithFallback(prompt, clips, enhanceAudioSchema, apiKey)
        aiJson.decodeFromString(stripJsonFence(extractText(payload)))
    }

    private fun callWithFallback(
        prompt: String,
        clips: List<EnhanceClip>,
        schema: JsonObject,
        apiKey: String,
    ): String {
        val first = call(model, prompt, clips, schema, apiKey)
        return if (first.code == 429 || first.code == 503) {
            call(fallbackModel, prompt, clips, schema, apiKey).requireBody()
        } else {
            first.requireBody()
        }
    }

    private fun call(
        modelName: String,
        prompt: String,
        clips: List<EnhanceClip>,
        schema: JsonObject,
        apiKey: String,
    ): HttpResult {
        val parts = buildJsonArray {
            add(buildJsonObject { put("text", JsonPrimitive(prompt)) })
            clips.forEach { clip ->
                add(buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", JsonPrimitive(clip.mimeType))
                        put(
                            "data",
                            JsonPrimitive(Base64.encodeToString(clip.file.readBytes(), Base64.NO_WRAP)),
                        )
                    })
                })
            }
        }
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", parts)
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(0.15))
                put("maxOutputTokens", JsonPrimitive(8192))
                put("responseMimeType", JsonPrimitive("application/json"))
                put("responseJsonSchema", schema)
            })
        }
        return execute(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/" + modelName + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(aiJson.encodeToString(JsonObject.serializer(), body).toRequestBody(enhanceJsonMediaType))
                .build()
        )
    }

    private fun execute(request: Request): HttpResult = client.newCall(request).execute().use { response ->
        HttpResult(response.code, response.body?.string().orEmpty())
    }

    private fun extractText(payload: String): String {
        val root = aiJson.parseToJsonElement(payload).jsonObject
        return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw AiEnhanceException("Gemini returned no Enhance result")
    }
}

private data class HttpResult(val code: Int, val body: String) {
    fun requireBody(): String {
        if (code !in 200..299) {
            val detail = runCatching {
                aiJson.parseToJsonElement(body).jsonObject["error"]?.let { error ->
                    if (error is JsonObject) error["message"]?.jsonPrimitive?.contentOrNull else error.jsonPrimitive.contentOrNull
                }
            }.getOrNull()
            throw AiEnhanceException(
                detail?.take(300) ?: "AI Enhance request failed (HTTP " + code + ")",
                retryable = code == 429 || code == 503 || code in 500..599,
            )
        }
        return body
    }
}

class AiEnhanceException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)
