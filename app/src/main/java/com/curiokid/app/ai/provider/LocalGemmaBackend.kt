package com.curiokid.app.ai.provider

import android.graphics.Bitmap

/**
 * Stub for running Gemma 4 fully on the device.
 *
 * Wiring a real local model in Android requires shipping a `.task` file via
 * MediaPipe Tasks GenAI (or an alternative inference runtime), which means
 * adding a heavyweight native dependency and a multi-gigabyte model
 * download. The provider abstraction is in place so that path can be
 * implemented without touching the rest of the app — for now this backend
 * fails fast with a clear message.
 */
internal class LocalGemmaBackend : LlmBackend {

    override suspend fun ask(
        systemPrompt: String,
        userText: String,
        image: Bitmap?,
        modelName: String,
    ): String = throw UnsupportedOperationException(LOCAL_NOT_READY)

    override suspend fun summarise(
        systemPrompt: String,
        rawHistoryText: String,
        modelName: String,
    ): String = throw UnsupportedOperationException(LOCAL_NOT_READY)

    companion object {
        const val LOCAL_NOT_READY: String =
            "On-device Gemma 4 isn't installed on this phone yet. " +
                "Ask a grown-up to download a Gemma 4 model file in Settings, " +
                "or switch to Google AI Studio or OpenRouter for now."
    }
}
