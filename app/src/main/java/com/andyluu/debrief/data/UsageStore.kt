package com.andyluu.debrief.data

import android.content.Context
import java.security.MessageDigest

data class LocalApiUsage(
    val keyId: String,
    val successfulRequests: Long,
    val audioDurationMs: Long,
)

class UsageStore(context: Context) {
    private val preferences = context.getSharedPreferences("api_usage", Context.MODE_PRIVATE)
    private val lock = Any()

    fun get(provider: String, apiKey: String): LocalApiUsage {
        val keyId = keyId(apiKey)
        val prefix = "${provider}_${keyId}"
        return LocalApiUsage(
            keyId = keyId,
            successfulRequests = preferences.getLong("${prefix}_requests", 0),
            audioDurationMs = preferences.getLong("${prefix}_audio_ms", 0),
        )
    }

    fun recordSuccess(provider: String, apiKey: String, audioDurationMs: Long) = synchronized(lock) {
        val current = get(provider, apiKey)
        val prefix = "${provider}_${current.keyId}"
        preferences.edit()
            .putLong("${prefix}_requests", current.successfulRequests + 1)
            .putLong("${prefix}_audio_ms", current.audioDurationMs + audioDurationMs.coerceAtLeast(0))
            .apply()
    }

    companion object {
        internal fun keyId(apiKey: String): String = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }
}
