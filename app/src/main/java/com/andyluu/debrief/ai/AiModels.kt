package com.andyluu.debrief.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class AiPassResponse(
    val recordingSummary: String,
    val suggestedFilename: String,
    val sets: List<AiSetResult>,
    val speakers: List<AiSpeakerResult> = emptyList(),
    val andySpeakerId: String? = null,
)

@Serializable
data class AiSetResult(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val summary: String,
    val speakerIds: List<String> = emptyList(),
)

@Serializable
data class AiSpeakerResult(
    val speakerId: String,
    val name: String,
    @SerialName("confidence") val confidence: String,
    val evidence: String = "",
)

internal val aiJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val aiResponseSchema: JsonObject = aiJson.parseToJsonElement(
    """{
      "type":"object",
      "properties":{
        "recordingSummary":{"type":"string"},
        "suggestedFilename":{"type":"string"},
        "sets":{"type":"array","items":{"type":"object","properties":{
          "startMs":{"type":"integer"},"endMs":{"type":"integer"},
          "title":{"type":"string"},"summary":{"type":"string"},
          "speakerIds":{"type":"array","items":{"type":"string"}}
        },"required":["startMs","endMs","title","summary","speakerIds"],"additionalProperties":false}},
        "speakers":{"type":"array","items":{"type":"object","properties":{
          "speakerId":{"type":"string"},"name":{"type":"string"},
          "confidence":{"type":"string","enum":["explicit","inferred","none"]},
          "evidence":{"type":"string"}
        },"required":["speakerId","name","confidence","evidence"],"additionalProperties":false}},
        "andySpeakerId":{"type":["string","null"]}
      },
      "required":["recordingSummary","suggestedFilename","sets","speakers","andySpeakerId"],
      "additionalProperties":false
    }""".trimIndent()
).jsonObject

internal fun stripJsonFence(value: String): String = value.trim()
    .removePrefix("```json").removePrefix("```")
    .removeSuffix("```").trim()

