package com.curiokid.app.ai.provider

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Gemma 4 hosted on OpenRouter (https://openrouter.ai/) via its
 * OpenAI-compatible chat-completions endpoint. Uses HttpURLConnection +
 * kotlinx.serialization so we don't pull in another HTTP client.
 *
 * Image input is intentionally ignored — Gemma chat models on OpenRouter
 * are text-only at the time of writing.
 */
internal class OpenRouterBackend(
    private val apiKey: String,
) : LlmBackend {

    override suspend fun ask(
        systemPrompt: String,
        userText: String,
        image: Bitmap?,
        modelName: String,
    ): String = withContext(Dispatchers.IO) {
        val text = userText.ifBlank { "Tell me something cool!" }
        val noteAboutImage = if (image != null) {
            "\n\n(Note: an image was attached; describe what a child might see in a friendly photo and answer the question generally.)"
        } else ""
        val response = chatCompletion(
            modelName = modelName,
            messages = listOf(
                ChatMessageJson(role = "system", content = systemPrompt),
                ChatMessageJson(role = "user", content = text + noteAboutImage),
            ),
            temperature = 0.7,
            maxTokens = 8192,
        )
        response.takeIf { it.isNotBlank() }
            ?: "Hmm, I'm not sure how to answer that one. Let's try a different question!"
    }

    override suspend fun summarise(
        systemPrompt: String,
        rawHistoryText: String,
        modelName: String,
    ): String = withContext(Dispatchers.IO) {
        chatCompletion(
            modelName = modelName,
            messages = listOf(
                ChatMessageJson(role = "system", content = systemPrompt),
                ChatMessageJson(role = "user", content = rawHistoryText),
            ),
            temperature = 0.4,
            maxTokens = 8192,
        ).ifBlank { "No digest available yet." }
    }

    private fun chatCompletion(
        modelName: String,
        messages: List<ChatMessageJson>,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val payload = ChatRequestJson(
            model = modelName,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )
        val body = JSON.encodeToString(payload)

        val url = URL("https://openrouter.ai/api/v1/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("HTTP-Referer", "https://github.com/curio-kid")
            setRequestProperty("X-Title", "Curio Kid")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { inStream ->
                BufferedReader(InputStreamReader(inStream, Charsets.UTF_8)).readText()
            }.orEmpty()

            if (code !in 200..299) {
                val parsedError = runCatching {
                    JSON.decodeFromString<ChatErrorJson>(raw).error?.message
                }.getOrNull()
                throw IOException(
                    "OpenRouter $code: ${parsedError ?: raw.take(280).ifBlank { "request failed" }}"
                )
            }

            val parsed = JSON.decodeFromString<ChatResponseJson>(raw)
            return parsed.choices.firstOrNull()?.message?.content.orEmpty().trim()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

@Serializable
private data class ChatRequestJson(
    val model: String,
    val messages: List<ChatMessageJson>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
)

@Serializable
private data class ChatMessageJson(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatResponseJson(
    val choices: List<ChatChoiceJson> = emptyList(),
)

@Serializable
private data class ChatChoiceJson(
    val message: ChatMessageJson? = null,
)

@Serializable
private data class ChatErrorJson(
    val error: ChatErrorBody? = null,
)

@Serializable
private data class ChatErrorBody(
    val message: String? = null,
    val code: String? = null,
)
