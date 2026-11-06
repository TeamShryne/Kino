package com.teamshryne.kino.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeepSeekCredentials(
    val token: String,
    val wafToken: String,
    val hifDliq: String,
    val hifLeim: String,
    val sessionId: String,
    val userAgent: String,
    val locale: String = "en_US",
    val timezoneOffset: String = "19800",
) {
    fun toCoreJson(): String = JSONObject()
        .put("provider", "deepseek")
        .put("token", token)
        .put("waf_token", wafToken)
        .put("hif_dliq", hifDliq)
        .put("hif_leim", hifLeim)
        .put("ds_session_id", sessionId)
        .put("user_agent", userAgent)
        .put("locale", locale)
        .put("timezone_offset", timezoneOffset)
        .toString()

    internal fun encode(): String = JSONObject()
        .put("token", token)
        .put("waf_token", wafToken)
        .put("hif_dliq", hifDliq)
        .put("hif_leim", hifLeim)
        .put("ds_session_id", sessionId)
        .put("user_agent", userAgent)
        .put("locale", locale)
        .put("timezone_offset", timezoneOffset)
        .toString()

    companion object {
        internal fun decode(raw: String) = JSONObject(raw).let { json ->
            DeepSeekCredentials(
                token = json.getString("token"),
                wafToken = json.getString("waf_token"),
                hifDliq = json.getString("hif_dliq"),
                hifLeim = json.getString("hif_leim"),
                sessionId = json.optString("ds_session_id"),
                userAgent = json.optString("user_agent"),
                locale = json.optString("locale", "en_US"),
                timezoneOffset = json.optString("timezone_offset", "19800"),
            )
        }
    }
}

/** AES-GCM encrypted preferences with a non-exportable Android Keystore key. */
class DeepSeekCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("deepseek-auth", Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: DeepSeekCredentials) {
        require(credentials.token.isNotBlank() && credentials.wafToken.isNotBlank())
        require(credentials.hifDliq.isNotBlank() && credentials.hifLeim.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(credentials.encode().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        preferences.edit().putString(KEY_CREDENTIALS, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun load(): DeepSeekCredentials? {
        val encoded = preferences.getString(KEY_CREDENTIALS, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, IV_LENGTH)))
            DeepSeekCredentials.decode(String(cipher.doFinal(packed.copyOfRange(IV_LENGTH, packed.size)), Charsets.UTF_8))
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().remove(KEY_CREDENTIALS).apply()
    }

    @Synchronized
    fun deviceId(): String = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "kino.deepseek.credentials.v1"
        const val KEY_CREDENTIALS = "encrypted_credentials"
        const val KEY_DEVICE_ID = "device_id"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
