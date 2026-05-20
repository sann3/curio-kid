package com.curiokid.app.ai.provider

import android.graphics.Bitmap
import com.curiokid.app.ai.local.LocalGemmaCatalog
import com.curiokid.app.ai.local.LocalGemmaEngine
import com.curiokid.app.ai.local.LocalModelManager

/**
 * Runs Gemma 4 fully on the device through MediaPipe LLM Inference.
 *
 * The heavy lifting (loading the multi-GB `.task` file, holding the
 * inference instance in memory, streaming tokens) lives in
 * [LocalGemmaEngine]. This class is the thin glue between the
 * provider-agnostic [LlmBackend] contract and the engine: it resolves the
 * model name into a [LocalGemmaCatalog.Variant], looks up its installed
 * file via [LocalModelManager], and fails fast with a kid-readable
 * message when the variant isn't downloaded yet.
 */
internal class LocalGemmaBackend(
    private val engine: LocalGemmaEngine,
    private val manager: LocalModelManager,
) : LlmBackend {

    override suspend fun ask(
        systemPrompt: String,
        history: List<ChatTurn>,
        userText: String,
        image: Bitmap?,
        modelName: String,
    ): String {
        val variant = resolveVariant(modelName)
        val file = manager.installedFile(variant)
            ?: throw UnsupportedOperationException(notInstalledMessage(variant))
        return engine.generate(
            variant = variant,
            modelFile = file,
            systemPrompt = systemPrompt,
            history = history,
            userText = userText,
            image = image,
        )
    }

    override suspend fun summarise(
        systemPrompt: String,
        rawHistoryText: String,
        modelName: String,
    ): String {
        val variant = resolveVariant(modelName)
        val file = manager.installedFile(variant)
            ?: throw UnsupportedOperationException(notInstalledMessage(variant))
        return engine.summarise(
            variant = variant,
            modelFile = file,
            systemPrompt = systemPrompt,
            rawHistoryText = rawHistoryText,
        )
    }

    private fun resolveVariant(modelName: String): LocalGemmaCatalog.Variant =
        LocalGemmaCatalog.byId(modelName)
            ?: throw UnsupportedOperationException(
                "Unknown on-device Gemma 4 variant: $modelName. Pick one in Settings."
            )

    private fun notInstalledMessage(variant: LocalGemmaCatalog.Variant): String =
        "${variant.displayName} isn't installed on this phone yet. " +
            "Open Settings → On-device Gemma 4 and tap Download model."
}
