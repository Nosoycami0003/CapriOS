package com.endrunners.capri

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CapriPrefs {
    private const val FILE_NAME = "capri_secure_prefs"
    private const val KEY_PROVIDER = "ai_provider"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_VISION_ENABLED = "vision_enabled"

    @Volatile
    private var cached: SharedPreferences? = null

    // Se crea una sola vez: construir MasterKey + EncryptedSharedPreferences en cada llamada es lento.
    private fun prefs(context: Context): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: run {
                val appContext = context.applicationContext
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cached = it }
            }
        }

    fun saveConfig(context: Context, provider: AIProvider, apiKey: String, model: String, visionEnabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model)
            .putBoolean(KEY_VISION_ENABLED, visionEnabled)
            .apply()
    }

    fun getProvider(context: Context): AIProvider =
        AIProvider.fromId(prefs(context).getString(KEY_PROVIDER, null))

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun getModel(context: Context): String {
        val saved = prefs(context).getString(KEY_MODEL, null)
        if (!saved.isNullOrBlank()) return saved
        return getProvider(context).defaultModel
    }

    fun isVisionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VISION_ENABLED, false)

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()
}
