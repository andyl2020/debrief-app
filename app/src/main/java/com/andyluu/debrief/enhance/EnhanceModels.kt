package com.andyluu.debrief.enhance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.andyluu.debrief.ai.aiJson

@Serializable
data class EnhanceTextResponse(
    val edits: List<EnhanceEdit> = emptyList(),
)

@Serializable
data class EnhanceEdit(
    val utteranceIds: List<String> = emptyList(),
    val span: EnhanceSpan,
    val original: String,
    val repaired: String? = null,
    val type: String,
    val confidence: String,
    val needsAudio: Boolean = false,
    val reason: String = "",
)

@Serializable
data class EnhanceSpan(
    val start: Double,
    val end: Double,
)

@Serializable
data class EnhanceAudioResponse(
    val results: List<EnhanceAudioResult> = emptyList(),
)

@Serializable
data class EnhanceAudioResult(
    val spanId: String,
    val heard: String? = null,
    val confidence: String = "low",
    val verdict: String? = null,
)

data class EnhanceClip(
    val spanId: String,
    val file: java.io.File,
    val mimeType: String = "audio/mp4",
)

internal val enhanceTextSchema: JsonObject = aiJson.parseToJsonElement(
    """{
      "type":"object",
      "properties":{
        "edits":{"type":"array","items":{"type":"object","properties":{
          "utteranceIds":{"type":"array","items":{"type":"string"}},
          "span":{"type":"object","properties":{"start":{"type":"number"},"end":{"type":"number"}},"required":["start","end"],"additionalProperties":false},
          "original":{"type":"string"},
          "repaired":{"type":["string","null"]},
          "type":{"type":"string","enum":["fix","merge","inaudible"]},
          "confidence":{"type":"string","enum":["high","medium","low"]},
          "needsAudio":{"type":"boolean"},
          "reason":{"type":"string"}
        },"required":["utteranceIds","span","original","repaired","type","confidence","needsAudio","reason"],"additionalProperties":false}}
      },
      "required":["edits"],
      "additionalProperties":false
    }""".trimIndent()
).jsonObject

internal val enhanceAudioSchema: JsonObject = aiJson.parseToJsonElement(
    """{
      "type":"object",
      "properties":{
        "results":{"type":"array","items":{"type":"object","properties":{
          "spanId":{"type":"string"},
          "heard":{"type":["string","null"]},
          "confidence":{"type":"string","enum":["high","medium","low"]},
          "verdict":{"type":["string","null"],"enum":["inaudible",null]}
        },"required":["spanId","heard","confidence","verdict"],"additionalProperties":false}}
      },
      "required":["results"],
      "additionalProperties":false
    }""".trimIndent()
).jsonObject
