package com.curiokid.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.curiokid.app.ai.provider.LlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Stores sensitive settings (Gemini / OpenRouter API keys, parent PIN) in
 * EncryptedSharedPreferences so they are encrypted at rest on the device.
 */
class SettingsManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob())

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _provider = MutableStateFlow(
        LlmProvider.fromId(prefs.getString(KEY_PROVIDER, LlmProvider.DEFAULT.id))
    )
    val provider: StateFlow<LlmProvider> = _provider.asStateFlow()

    private val _googleApiKey = MutableStateFlow(prefs.getString(KEY_GOOGLE_API, null))
    val googleApiKey: StateFlow<String?> = _googleApiKey.asStateFlow()

    private val _openRouterApiKey = MutableStateFlow(prefs.getString(KEY_OPENROUTER_API, null))
    val openRouterApiKey: StateFlow<String?> = _openRouterApiKey.asStateFlow()

    private val _googleModel = MutableStateFlow(
        normalizeModel(prefs.getString(KEY_GOOGLE_MODEL, null), GOOGLE_MODELS)
    )
    val googleModel: StateFlow<String> = _googleModel.asStateFlow()

    private val _openRouterModel = MutableStateFlow(
        normalizeModel(prefs.getString(KEY_OPENROUTER_MODEL, null), OPEN_ROUTER_MODELS)
    )
    val openRouterModel: StateFlow<String> = _openRouterModel.asStateFlow()

    private val _localModel = MutableStateFlow(
        normalizeModel(prefs.getString(KEY_LOCAL_MODEL, null), LOCAL_MODELS)
    )
    val localModel: StateFlow<String> = _localModel.asStateFlow()

    private val _parentPin = MutableStateFlow(prefs.getString(KEY_PIN, null))
    val parentPin: StateFlow<String?> = _parentPin.asStateFlow()

    private val _debugMode = MutableStateFlow(prefs.getBoolean(KEY_DEBUG, false))
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    /**
     * API key for whichever provider is active right now. Null means either
     * no key is configured yet, or the active provider doesn't need one
     * (e.g. on-device).
     */
    val activeApiKey: StateFlow<String?> = combine(
        _provider, _googleApiKey, _openRouterApiKey,
    ) { provider, google, openrouter ->
        when (provider) {
            LlmProvider.GOOGLE_AI_STUDIO -> google
            LlmProvider.OPEN_ROUTER -> openrouter
            LlmProvider.LOCAL -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, _googleApiKey.value)

    /** Model name for the currently active provider. */
    val activeModel: StateFlow<String> = combine(
        _provider, _googleModel, _openRouterModel, _localModel,
    ) { provider, google, openrouter, local ->
        when (provider) {
            LlmProvider.GOOGLE_AI_STUDIO -> google
            LlmProvider.OPEN_ROUTER -> openrouter
            LlmProvider.LOCAL -> local
        }
    }.stateIn(scope, SharingStarted.Eagerly, _googleModel.value)

    fun setProvider(value: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, value.id).apply()
        _provider.value = value
    }

    fun setGoogleApiKey(value: String?) {
        val cleaned = value?.trim()?.ifBlank { null }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_GOOGLE_API) else putString(KEY_GOOGLE_API, cleaned)
            apply()
        }
        _googleApiKey.value = cleaned
    }

    fun setOpenRouterApiKey(value: String?) {
        val cleaned = value?.trim()?.ifBlank { null }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_OPENROUTER_API) else putString(KEY_OPENROUTER_API, cleaned)
            apply()
        }
        _openRouterApiKey.value = cleaned
    }

    fun setGoogleModel(value: String) {
        val normalized = normalizeModel(value, GOOGLE_MODELS)
        prefs.edit().putString(KEY_GOOGLE_MODEL, normalized).apply()
        _googleModel.value = normalized
    }

    fun setOpenRouterModel(value: String) {
        val normalized = normalizeModel(value, OPEN_ROUTER_MODELS)
        prefs.edit().putString(KEY_OPENROUTER_MODEL, normalized).apply()
        _openRouterModel.value = normalized
    }

    fun setLocalModel(value: String) {
        val normalized = normalizeModel(value, LOCAL_MODELS)
        prefs.edit().putString(KEY_LOCAL_MODEL, normalized).apply()
        _localModel.value = normalized
    }

    fun setParentPin(pin: String?) {
        prefs.edit().apply {
            if (pin.isNullOrBlank()) remove(KEY_PIN) else putString(KEY_PIN, pin)
            apply()
        }
        _parentPin.value = pin?.ifBlank { null }
    }

    fun setDebugMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG, enabled).apply()
        _debugMode.value = enabled
    }

    fun verifyPin(input: String): Boolean {
        val current = _parentPin.value ?: return false
        return current == input
    }

    fun modelsFor(provider: LlmProvider): List<String> = when (provider) {
        LlmProvider.GOOGLE_AI_STUDIO -> GOOGLE_MODELS
        LlmProvider.OPEN_ROUTER -> OPEN_ROUTER_MODELS
        LlmProvider.LOCAL -> LOCAL_MODELS
    }

    companion object {
        private const val PREFS_NAME = "curio_kid_secure_prefs"

        private const val KEY_PROVIDER = "llm_provider"
        private const val KEY_GOOGLE_API = "gemini_api_key"
        private const val KEY_OPENROUTER_API = "openrouter_api_key"
        private const val KEY_GOOGLE_MODEL = "google_gemma_model"
        private const val KEY_OPENROUTER_MODEL = "openrouter_gemma_model"
        private const val KEY_LOCAL_MODEL = "local_gemma_model"
        private const val KEY_PIN = "parent_pin"
        private const val KEY_DEBUG = "debug_mode"

        /**
         * Gemma 4 variants exposed through the Gemini API. As of April 2026
         * Google publishes two: the 26B mixture-of-experts (fast, default)
         * and the 31B dense (slower, higher quality). Both are multimodal
         * with a 256K context window and Apache-2.0 licensed.
         */
        val GOOGLE_MODELS = listOf(
            "gemma-4-26b-a4b-it",
            "gemma-4-31b-it",
        )

        /**
         * Gemma 4 variants routable through OpenRouter. OpenRouter prefixes
         * the upstream provider, so the slugs are `google/gemma-4-…`.
         */
        val OPEN_ROUTER_MODELS = listOf(
            "google/gemma-4-26b-a4b-it",
            "google/gemma-4-31b-it",
        )

        /**
         * Gemma 4 sizes that can realistically run fully on-device once a
         * compatible `.task` file is downloaded. Quantised int4 builds are
         * what MediaPipe Tasks GenAI ships.
         */
        val LOCAL_MODELS = listOf(
            "gemma-4-2b-it-int4",
            "gemma-4-7b-it-int4",
        )

        const val DEFAULT_MODEL = "gemma-4-26b-a4b-it"

        private fun normalizeModel(raw: String?, allowed: List<String>): String {
            val trimmed = raw?.trim()?.ifBlank { null }
            return if (trimmed != null && trimmed in allowed) trimmed else allowed.first()
        }
    }
}
