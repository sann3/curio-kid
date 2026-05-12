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

    /** Single-turn completion for the child-facing chat. */
    suspend fun ask(
        systemPrompt: String,
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
