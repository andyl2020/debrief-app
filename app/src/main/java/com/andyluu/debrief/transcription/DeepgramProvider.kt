package com.andyluu.debrief.transcription

import android.content.Context
import com.andyluu.debrief.data.TranscriptSegmentEntity
import com.andyluu.debrief.data.TranscriptWordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DeepgramProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : TranscriptionProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(
        context: Context,
        recordingId: String,
        audioBody: okhttp3.RequestBody,
        mimeType: String,
        apiKey: String,
        keyterms: List<String>,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val url = requestUrl(keyterms)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .post(audioBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject["err_msg"]?.jsonPrimitive?.content
                }.getOrNull()
                throw TranscriptionException(message ?: "Deepgram request failed (${response.code})")
            }
            parse(recordingId, body)
        }
    }

    internal fun requestUrl(keyterms: List<String>): HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.deepgram.com")
            .addPathSegments("v1/listen")
            .addQueryParameter("model", "nova-3")
            .addQueryParameter("language", "en")
            .addQueryParameter("diarize_model", "latest")
            .addQueryParameter("utterances", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("smart_format", "true")
            .apply { keyterms.take(100).filter(String::isNotBlank).forEach { addQueryParameter("keyterm", it.trim()) } }
            .build()

    internal fun parse(recordingId: String, payload: String): TranscriptionResult {
        val root = json.parseToJsonElement(payload).jsonObject
        val results = root["results"]?.jsonObject ?: throw TranscriptionException("Deepgram returned no results")
        val utterances = results["utterances"] as? JsonArray ?: JsonArray(emptyList())
        val segments = utterances.mapNotNull { element ->
            val item = element.jsonObject
            val text = item.string("transcript")?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            TranscriptSegmentEntity(
                recordingId = recordingId,
                speakerId = speakerLabel(item.int("speaker") ?: 0),
                startMs = item.seconds("start"),
                endMs = item.seconds("end"),
                text = text,
            )
        }
        val channelWords = results["channels"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject?.get("words") as? JsonArray
        val utteranceWords = utterances.flatMap { (it.jsonObject["words"] as? JsonArray).orEmpty() }
        val wordsJson = channelWords?.toList() ?: utteranceWords
        val words = wordsJson.mapNotNull { element ->
            val item = element.jsonObject
            val text = (item.string("punctuated_word") ?: item.string("word"))?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            TranscriptWordEntity(
                recordingId = recordingId,
                speakerId = speakerLabel(item.int("speaker") ?: 0),
                startMs = item.seconds("start"),
                endMs = item.seconds("end"),
                text = text,
                confidence = item.double("confidence"),
            )
        }
        if (segments.isEmpty() && words.isEmpty()) throw TranscriptionException("No speech was detected")
        // The channel-level word stream is Deepgram's complete recognized timeline. In some
        // responses the convenience utterance list omits words, so deriving display segments
        // from all words prevents recognized sections from disappearing between utterances.
        return TranscriptionResult(segmentsFromWords(recordingId, words).ifEmpty { segments }, words)
    }

    private fun speakerLabel(index: Int) = "Speaker ${('A'.code + index).toChar()}"
    private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.content
    private fun JsonObject.int(name: String) = get(name)?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(name: String) = get(name)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.seconds(name: String) = ((get(name)?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
}
