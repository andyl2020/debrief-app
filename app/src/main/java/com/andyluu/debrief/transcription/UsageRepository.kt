package com.andyluu.debrief.transcription

import com.andyluu.debrief.data.LocalApiUsage
import com.andyluu.debrief.data.UsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

data class ApiUsageSnapshot(
    val provider: String,
    val keyId: String,
    val localRequests: Long,
    val localAudioMs: Long,
    val periodStart: LocalDate? = null,
    val providerRequests: Long? = null,
    val providerAudioHours: Double? = null,
    val providerSpendUsd: Double? = null,
    val balanceUsd: Double? = null,
    val providerMessage: String? = null,
)

class UsageRepository(
    private val usageStore: UsageStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(provider: String, apiKey: String): ApiUsageSnapshot = withContext(Dispatchers.IO) {
        val local = usageStore.get(provider, apiKey)
        if (provider == "deepgram") loadDeepgram(apiKey, local)
        else ApiUsageSnapshot(
            provider = provider,
            keyId = local.keyId,
            localRequests = local.successfulRequests,
            localAudioMs = local.audioDurationMs,
            providerMessage = "AssemblyAI exposes detailed usage and spend in its dashboard. Local successful usage is shown here.",
        )
    }

    private fun loadDeepgram(apiKey: String, local: LocalApiUsage): ApiUsageSnapshot {
        val base = ApiUsageSnapshot(
            provider = "deepgram",
            keyId = local.keyId,
            localRequests = local.successfulRequests,
            localAudioMs = local.audioDurationMs,
        )
        return try {
            val auth = get("https://api.deepgram.com/v1/auth/token", apiKey)
            val accessor = auth["accessor"]?.jsonPrimitive?.contentOrNull
            val projects = get("https://api.deepgram.com/v1/projects", apiKey)["projects"]?.jsonArray.orEmpty()
            if (projects.isEmpty()) return base.copy(providerMessage = "No Deepgram project is available to this API key.")

            val today = LocalDate.now(ZoneOffset.UTC)
            val start = today.withDayOfMonth(1)
            var requests = 0L
            var hours = 0.0
            var spend = 0.0
            var balance = 0.0
            var usageAvailable = false
            var billingAvailable = false
            var balanceAvailable = false
            var accessDenied = false

            projects.forEach { projectElement ->
                val projectId = projectElement.jsonObject["project_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val common = "https://api.deepgram.com/v1/projects/$projectId"
                runCatching {
                    get(
                        "$common/usage/breakdown",
                        apiKey,
                        mapOf(
                            "start" to start.toString(),
                            "end" to today.toString(),
                            "endpoint" to "listen",
                            "accessor" to accessor,
                        ),
                    )
                }.onSuccess { payload ->
                    val parsed = parseDeepgramUsage(payload)
                    requests += parsed.first
                    hours += parsed.second
                    usageAvailable = true
                }.onFailure { accessDenied = accessDenied || (it as? UsageApiException)?.code == 403 }

                runCatching {
                    get(
                        "$common/billing/breakdown",
                        apiKey,
                        mapOf("start" to start.toString(), "end" to today.toString(), "accessor" to accessor),
                    )
                }.onSuccess { payload ->
                    spend += parseDeepgramBilling(payload)
                    billingAvailable = true
                }.onFailure { accessDenied = accessDenied || (it as? UsageApiException)?.code == 403 }

                runCatching { get("$common/balances", apiKey) }
                    .onSuccess { payload ->
                        balance += parseDeepgramBalances(payload)
                        balanceAvailable = true
                    }
                    .onFailure { accessDenied = accessDenied || (it as? UsageApiException)?.code == 403 }
            }

            base.copy(
                periodStart = start,
                providerRequests = requests.takeIf { usageAvailable },
                providerAudioHours = hours.takeIf { usageAvailable },
                providerSpendUsd = spend.takeIf { billingAvailable },
                balanceUsd = balance.takeIf { balanceAvailable },
                providerMessage = if (accessDenied) {
                    "This key can transcribe but cannot read all account usage. Add usage:read and billing:read scopes in Deepgram to show provider totals."
                } else null,
            )
        } catch (error: UsageApiException) {
            base.copy(providerMessage = when (error.code) {
                401 -> "Deepgram rejected this API key. Save a valid key and refresh."
                403 -> "This key can transcribe but lacks permission to read usage. Add usage:read and billing:read scopes in Deepgram."
                else -> "Deepgram usage is temporarily unavailable (HTTP ${error.code}). Local usage is still shown."
            })
        } catch (_: Throwable) {
            base.copy(providerMessage = "Could not refresh provider usage. Local successful usage is still shown.")
        }
    }

    private fun get(url: String, apiKey: String, parameters: Map<String, String?> = emptyMap()): JsonObject {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            parameters.forEach { (name, value) -> if (!value.isNullOrBlank()) addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(httpUrl)
            .header("Authorization", "Token $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UsageApiException(response.code)
            json.parseToJsonElement(body).jsonObject
        }
    }

}

private class UsageApiException(val code: Int) : Exception("Provider usage request failed")

internal fun parseDeepgramUsage(payload: JsonObject): Pair<Long, Double> {
    var requests = 0L
    var hours = 0.0
    payload["results"]?.jsonArray.orEmpty().forEach { element ->
        val item = element.jsonObject
        requests += item["requests"]?.jsonPrimitive?.longOrNull ?: 0
        hours += item["total_hours"]?.jsonPrimitive?.doubleOrNull
            ?: item["hours"]?.jsonPrimitive?.doubleOrNull
            ?: 0.0
    }
    return requests to hours
}

internal fun parseDeepgramBilling(payload: JsonObject): Double = payload["results"]?.jsonArray.orEmpty()
    .sumOf { it.jsonObject["dollars"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }

internal fun parseDeepgramBalances(payload: JsonObject): Double = payload["balances"]?.jsonArray.orEmpty()
    .filter { it.jsonObject["units"]?.jsonPrimitive?.contentOrNull.equals("USD", ignoreCase = true) }
    .sumOf { it.jsonObject["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
