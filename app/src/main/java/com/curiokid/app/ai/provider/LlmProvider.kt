package com.curiokid.app.ai.provider

/**
 * Where Luna fetches her answers from. Curio Kid only ever talks to Gemma 4
 * variants, but the same family is reachable through three different
 * back-ends: Google AI Studio (managed cloud), OpenRouter (third-party
 * router), or a model running fully on the device.
 */
enum class LlmProvider(val id: String, val displayName: String) {
    GOOGLE_AI_STUDIO("google", "Google AI Studio"),
    OPEN_ROUTER("openrouter", "OpenRouter"),
    LOCAL("local", "On-device (local)");

    companion object {
        val DEFAULT = GOOGLE_AI_STUDIO

        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
