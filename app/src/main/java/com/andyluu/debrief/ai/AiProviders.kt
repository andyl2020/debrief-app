package com.andyluu.debrief.ai

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

private val jsonMediaType = "application/json".toMediaType()

private val aiHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(2, TimeUnit.MINUTES)
    .build()

class GeminiAiProvider(
    private val model: String = "gemini-2.5-flash",
    private val fallbackModel: String = "gemini-2.5-flash-lite",
    private val client: OkHttpClient = aiHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/models",
) : AiPassProvider {
    override suspend fun analyze(request: AiPassRequest, apiKey: String): AiPassResponse = withContext(Dispatchers.IO) {
        val first = call(model, request, apiKey)
        val payload = if (first.code == 429 || first.code == 503) {
            call(fallbackModel, request, apiKey).requireBody()
        } else {
            first.requireBody()
        }
        val root = aiJson.parseToJsonElement(payload).jsonObject
        val text = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw AiPassException("Gemini returned no structured result")
        aiJson.decodeFromString(stripJsonFence(text))
    }

    private fun call(modelName: String, request: AiPassRequest, apiKey: String): HttpResult {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(request.prompt)) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", JsonPrimitive("application/json"))
                put("responseJsonSchema", request.schema)
            })
        }
        return execute(
            client,
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/" + modelName + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .post(aiJson.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
                .build()
        )
    }
}

class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val model: String,
    private val client: OkHttpClient = aiHttpClient,
) : AiPassProvider {
    override suspend fun analyze(request: AiPassRequest, apiKey: String): AiPassResponse = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "The OpenAI-compatible base URL must use HTTPS" }
        require(model.isNotBlank()) { "Add a model name for the OpenAI-compatible provider" }
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val responseFormat = buildJsonObject {
            put("type", JsonPrimitive("json_schema"))
            put("json_schema", buildJsonObject {
                put("name", JsonPrimitive("debrief_ai_pass"))
                put("strict", JsonPrimitive(true))
                put("schema", request.schema)
            })
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(request.prompt))
                })
            })
            put("response_format", responseFormat)
        }
        val payload = execute(
            client,
            Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .post(aiJson.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
                .build()
        ).requireBody()
        val root = aiJson.parseToJsonElement(payload).jsonObject
        val text = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiPassException("The OpenAI-compatible provider returned no result")
        aiJson.decodeFromString(stripJsonFence(text))
    }
}

class AnthropicAiProvider(
    private val model: String = "claude-haiku-4-5",
    private val client: OkHttpClient = aiHttpClient,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : AiPassProvider {
    override suspend fun analyze(request: AiPassRequest, apiKey: String): AiPassResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", JsonPrimitive(model.ifBlank { "claude-haiku-4-5" }))
            put("max_tokens", JsonPrimitive(16_000))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(request.prompt))
                })
            })
            put("output_config", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("schema", request.schema)
                })
            })
        }
        val payload = execute(
            client,
            Request.Builder().url(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(aiJson.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
                .build()
        ).requireBody()
        val root = aiJson.parseToJsonElement(payload).jsonObject
        val text = root["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?: throw AiPassException("Claude returned no structured result")
        aiJson.decodeFromString(stripJsonFence(text))
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
            throw AiPassException(detail?.take(300) ?: "AI provider request failed (HTTP " + code + ")")
        }
        return body
    }
}

private fun execute(client: OkHttpClient, request: Request): HttpResult = client.newCall(request).execute().use { response ->
    HttpResult(response.code, response.body?.string().orEmpty())
}
