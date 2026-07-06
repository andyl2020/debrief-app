package com.andyluu.debrief.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

data class AppSettings(
    val folderUri: String? = null,
    val allowMobileData: Boolean = false,
    val keyterms: String = "",
    val provider: String = "deepgram",
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val folderUri = stringPreferencesKey("folder_uri")
        val mobileData = booleanPreferencesKey("allow_mobile_data")
        val keyterms = stringPreferencesKey("keyterms")
        val provider = stringPreferencesKey("provider")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            folderUri = prefs[Keys.folderUri],
            allowMobileData = prefs[Keys.mobileData] ?: false,
            keyterms = prefs[Keys.keyterms] ?: "",
            provider = prefs[Keys.provider] ?: "deepgram",
        )
    }

    suspend fun setFolderUri(uri: String) = context.dataStore.edit { it[Keys.folderUri] = uri }
    suspend fun setAllowMobileData(allow: Boolean) = context.dataStore.edit { it[Keys.mobileData] = allow }
    suspend fun setKeyterms(value: String) = context.dataStore.edit { it[Keys.keyterms] = value }
    suspend fun setProvider(value: String) = context.dataStore.edit { it[Keys.provider] = value }
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
        prefs.edit().putString(name, payload).apply()
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
