package com.andyluu.debrief.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("debrief_settings")

enum class TranscriptionAudioQuality(val storedValue: String, val bitrate: Int?) {
    ORIGINAL("original", null),
    BALANCED("balanced", 96_000),
    DATA_SAVER("data_saver", 64_000);

    companion object {
        fun fromStoredValue(value: String?): TranscriptionAudioQuality = entries
            .firstOrNull { it.storedValue == value } ?: ORIGINAL
    }
}

data class AppSettings(
    val folderUri: String? = null,
    val allowMobileData: Boolean = false,
    val keyterms: String = "",
    val provider: String = "assemblyai",
    val transcriptionAudioQuality: TranscriptionAudioQuality = TranscriptionAudioQuality.ORIGINAL,
    val aiProvider: String = "gemini",
    val aiEnhanceEnabled: Boolean = false,
    val aiAutoRun: Boolean = false,
    val aiAudioRelisten: Boolean = true,
    val aiGapMinutes: Int = 3,
    val openAiBaseUrl: String = "",
    val openAiModel: String = "",
    val anthropicModel: String = "claude-haiku-4-5",
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val folderUri = stringPreferencesKey("folder_uri")
        val mobileData = booleanPreferencesKey("allow_mobile_data")
        val keyterms = stringPreferencesKey("keyterms")
        val provider = stringPreferencesKey("provider")
        val transcriptionAudioQuality = stringPreferencesKey("transcription_audio_quality")
        val aiProvider = stringPreferencesKey("ai_provider")
        val aiEnhanceEnabled = booleanPreferencesKey("ai_enhance_enabled")
        val aiAutoRun = booleanPreferencesKey("ai_auto_run")
        val aiAudioRelisten = booleanPreferencesKey("ai_audio_relisten")
        val aiGapMinutes = intPreferencesKey("ai_gap_minutes")
        val openAiBaseUrl = stringPreferencesKey("openai_base_url")
        val openAiModel = stringPreferencesKey("openai_model")
        val anthropicModel = stringPreferencesKey("anthropic_model")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            folderUri = prefs[Keys.folderUri],
            allowMobileData = prefs[Keys.mobileData] ?: false,
            keyterms = prefs[Keys.keyterms] ?: "",
            provider = prefs[Keys.provider] ?: "assemblyai",
            transcriptionAudioQuality = TranscriptionAudioQuality.fromStoredValue(prefs[Keys.transcriptionAudioQuality]),
            aiProvider = prefs[Keys.aiProvider] ?: "gemini",
            aiEnhanceEnabled = prefs[Keys.aiEnhanceEnabled] ?: false,
            aiAutoRun = prefs[Keys.aiAutoRun] ?: false,
            aiAudioRelisten = prefs[Keys.aiAudioRelisten] ?: true,
            aiGapMinutes = (prefs[Keys.aiGapMinutes] ?: 3).coerceIn(1, 10),
            openAiBaseUrl = prefs[Keys.openAiBaseUrl] ?: "",
            openAiModel = prefs[Keys.openAiModel] ?: "",
            anthropicModel = prefs[Keys.anthropicModel] ?: "claude-haiku-4-5",
        )
    }

    suspend fun setFolderUri(uri: String) = context.dataStore.edit { it[Keys.folderUri] = uri }
    suspend fun setAllowMobileData(allow: Boolean) = context.dataStore.edit { it[Keys.mobileData] = allow }
    suspend fun setKeyterms(value: String) = context.dataStore.edit { it[Keys.keyterms] = value }
    suspend fun setProvider(value: String) = context.dataStore.edit { it[Keys.provider] = value }
    suspend fun setTranscriptionAudioQuality(value: TranscriptionAudioQuality) = context.dataStore.edit {
        it[Keys.transcriptionAudioQuality] = value.storedValue
    }
    suspend fun setAiProvider(value: String) = context.dataStore.edit { it[Keys.aiProvider] = value }
    suspend fun setAiEnhanceEnabled(value: Boolean) = context.dataStore.edit { it[Keys.aiEnhanceEnabled] = value }
    suspend fun setAiAutoRun(value: Boolean) = context.dataStore.edit { it[Keys.aiAutoRun] = value }
    suspend fun setAiAudioRelisten(value: Boolean) = context.dataStore.edit { it[Keys.aiAudioRelisten] = value }
    suspend fun setAiGapMinutes(value: Int) = context.dataStore.edit { it[Keys.aiGapMinutes] = value.coerceIn(1, 10) }
    suspend fun setOpenAiBaseUrl(value: String) = context.dataStore.edit { it[Keys.openAiBaseUrl] = value.trim() }
    suspend fun setOpenAiModel(value: String) = context.dataStore.edit { it[Keys.openAiModel] = value.trim() }
    suspend fun setAnthropicModel(value: String) = context.dataStore.edit { it[Keys.anthropicModel] = value.trim() }
}

class SecureSecretStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
    private val keyAlias = "debrief_api_secrets_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    fun put(name: String, value: String) {
        require(value.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString(name, payload).commit()) { "Could not save the API key securely." }
    }

    fun get(name: String): String? = runCatching {
        val bytes = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrNull()

    fun has(name: String): Boolean = prefs.contains(name)
    fun remove(name: String) = prefs.edit().remove(name).apply()
}
