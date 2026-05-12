package com.curiokid.app.ai.provider

import android.graphics.Bitmap
import com.curiokid.app.data.debug.DebugLog
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Talks to Gemma 4 hosted on Google AI Studio via the official
 * generativeai Kotlin SDK.
 */
internal class GoogleAiStudioBackend(
    private val apiKey: String,
) : LlmBackend {

    private val safety = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.LOW_AND_ABOVE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.LOW_AND_ABOVE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.LOW_AND_ABOVE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.LOW_AND_ABOVE),
    )

    override suspend fun ask(
        systemPrompt: String,
        userText: String,
        image: Bitmap?,
        modelName: String,
    ): String = withContext(Dispatchers.IO) {
        val model = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.7f
                topK = 40
                topP = 0.95f
                // Gemma sometimes burns hundreds of tokens on internal
                // planning before it produces the final paragraph (we strip
                // any leakage afterwards). 8192 is Google's recommended
                // ceiling for non-streaming chat — plenty of headroom so we
                // don't trip ResponseStoppedException(MAX_TOKENS).
                maxOutputTokens = 8192
            },
            safetySettings = safety,
            systemInstruction = content { text(systemPrompt) },
        )
        val prompt = content {
            if (image != null) image(image)
            text(userText.ifBlank { "Tell me something cool!" })
        }
        val response = model.generateContent(prompt)
        response.bestText()
            ?: "Hmm, I'm not sure how to answer that one. Let's try a different question!"
    }

    override suspend fun summarise(
        systemPrompt: String,
        rawHistoryText: String,
        modelName: String,
    ): String = withContext(Dispatchers.IO) {
        val model = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.4f
                maxOutputTokens = 8192
            },
            safetySettings = safety,
            systemInstruction = content { text(systemPrompt) },
        )
        val response = model.generateContent(content { text(rawHistoryText) })
        response.bestText() ?: "No digest available yet."
    }

    /**
     * `GenerateContentResponse.text` throws a `ResponseStoppedException`
     * when the finish reason is anything other than STOP — most commonly
     * `MAX_TOKENS`, which is recoverable. Try the convenience getter first,
     * but if it throws, dig the partial text out of `candidates` so the
     * child still sees the answer that was generated.
     */
    private fun GenerateContentResponse.bestText(): String? {
        val text = try {
            text
        } catch (stopped: ResponseStoppedException) {
            DebugLog.w(
                tag = "GoogleAI",
                message = "Generation stopped early (${stopped.message ?: "unknown reason"}); using partial text.",
                throwable = stopped,
            )
            partialTextOrNull()
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun GenerateContentResponse.partialTextOrNull(): String? {
        val candidate = candidates.firstOrNull() ?: return null
        return candidate.content.parts
            .filterIsInstance<TextPart>()
            .joinToString(separator = "") { it.text }
            .takeIf { it.isNotBlank() }
    }
}
