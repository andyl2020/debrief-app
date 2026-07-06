package com.andyluu.debrief.ai

import kotlinx.serialization.json.JsonObject

data class AiPassRequest(
    val prompt: String,
    val schema: JsonObject = aiResponseSchema,
)

interface AiPassProvider {
    suspend fun analyze(request: AiPassRequest, apiKey: String): AiPassResponse
}

class AiPassException(message: String) : Exception(message)

