package com.andyluu.debrief.transcription

import android.content.Context
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class AssemblyAiProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : TranscriptionProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun transcribe(
        context: Context,
        recordingId: String,
        audioFile: File,
        mimeType: String,
        apiKey: String,
        keyterms: List<String>,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val upload = call(
            Request.Builder().url("https://api.assemblyai.com/v2/upload")
                .header("authorization", apiKey)
                .post(audioFile.asRequestBody("application/octet-stream".toMediaType()))
                .build()
        )
        val uploadUrl = upload.jsonObject["upload_url"]?.jsonPrimitive?.content
            ?: throw TranscriptionException("AssemblyAI upload did not return a URL")
        val requestBody = buildJsonObject {
            put("audio_url", uploadUrl)
            put("speaker_labels", true)
            if (keyterms.isNotEmpty()) {
                putJsonArray("word_boost") { keyterms.take(100).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                put("boost_param", "high")
            }
        }.toString().toRequestBody(jsonType)
        val created = call(
            Request.Builder().url("https://api.assemblyai.com/v2/transcript")
                .header("authorization", apiKey).post(requestBody).build()
        )
        val id = created.jsonObject["id"]?.jsonPrimitive?.content
            ?: throw TranscriptionException("AssemblyAI did not return a transcript ID")

        repeat(360) {
            delay(10_000)
            val result = call(
                Request.Builder().url("https://api.assemblyai.com/v2/transcript/$id")
                    .header("authorization", apiKey).get().build()
            ).jsonObject
            when (result["status"]?.jsonPrimitive?.content) {
                "completed" -> return@withContext parse(recordingId, result)
                "error" -> throw TranscriptionException(result["error"]?.jsonPrimitive?.content ?: "AssemblyAI failed")
            }
        }
        throw TranscriptionException("AssemblyAI timed out while processing the recording")
    }

    private fun call(request: Request) = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw TranscriptionException("AssemblyAI request failed (${response.code})")
        json.parseToJsonElement(body)
    }

    internal fun parse(recordingId: String, root: kotlinx.serialization.json.JsonObject): TranscriptionResult {
        val words = root["words"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val text = item["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            TranscriptWordEntity(
                recordingId = recordingId,
                speakerId = "Speaker ${item["speaker"]?.jsonPrimitive?.contentOrNull ?: "A"}",
                startMs = item["start"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                endMs = item["end"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                text = text,
            )
        }
        val segments = root["utterances"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val text = item["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            TranscriptSegmentEntity(
                recordingId = recordingId,
                speakerId = "Speaker ${item["speaker"]?.jsonPrimitive?.contentOrNull ?: "A"}",
                startMs = item["start"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                endMs = item["end"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0,
                text = text,
            )
        }
        if (segments.isEmpty()) throw TranscriptionException("AssemblyAI returned no speech")
        return TranscriptionResult(segments, words)
    }
}
