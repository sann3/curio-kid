package com.curiokid.app.ai.provider

import android.graphics.Bitmap

/**
 * Common contract for any back-end that can answer a child's question or
 * summarise the day's questions for a parent.
 *
 * Implementations only worry about the wire / inference layer. Persona,
 * safety prompt, response cleaning, and friendly error mapping all live in
 * `LunaAI`, so providers stay tiny.
 */
internal interface LlmBackend {

    /**
     * Multi-turn completion for the child-facing chat.
     *
     * `history` is the conversation so far (oldest first), NOT including
     * the new `userText` being asked now. Backends that support chat
     * (Google AI Studio, OpenRouter) wire it into their respective
     * conversation APIs so follow-up questions like "tell me more" can
     * resolve against earlier turns.
     */
    suspend fun ask(
        systemPrompt: String,
        history: List<ChatTurn>,
        userText: String,
        image: Bitmap?,
        modelName: String,
    ): String

    /** Single-turn summarisation for the parent dashboard. */
    suspend fun summarise(
        systemPrompt: String,
        rawHistoryText: String,
        modelName: String,
    ): String
}

/**
 * A single completed turn in the chat, used as conversation history for
 * the next call. Images from prior turns are deliberately omitted — only
 * the most recent message's image is forwarded, so we don't re-upload
 * large bitmaps on every follow-up.
 */
data class ChatTurn(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT }
}
