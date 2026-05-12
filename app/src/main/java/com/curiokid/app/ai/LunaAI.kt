package com.curiokid.app.ai

import android.graphics.Bitmap
import android.util.Log
import com.curiokid.app.ai.provider.GoogleAiStudioBackend
import com.curiokid.app.ai.provider.LlmBackend
import com.curiokid.app.ai.provider.LlmProvider
import com.curiokid.app.ai.provider.LocalGemmaBackend
import com.curiokid.app.ai.provider.OpenRouterBackend
import com.curiokid.app.data.debug.DebugLog

/**
 * Luna's brain. Owns the persona/safety prompt, response cleaning, and
 * friendly error mapping; delegates the actual call to whichever provider
 * the user has chosen (Google AI Studio, OpenRouter, or on-device).
 */
class LunaAI(
    private val provider: LlmProvider,
    private val apiKey: String?,
    private val modelName: String,
) {

    private val backend: LlmBackend = when (provider) {
        LlmProvider.GOOGLE_AI_STUDIO -> GoogleAiStudioBackend(apiKey.orEmpty())
        LlmProvider.OPEN_ROUTER -> OpenRouterBackend(apiKey.orEmpty())
        LlmProvider.LOCAL -> LocalGemmaBackend()
    }

    suspend fun ask(
        question: String,
        image: Bitmap? = null,
    ): Result<String> = runCatching {
        val raw = backend.ask(
            systemPrompt = SystemPrompt.LUNA,
            userText = question,
            image = image,
            modelName = modelName,
        )
        cleanLunaReply(raw)
    }

    suspend fun summariseForParent(rawHistoryText: String): Result<String> =
        runCatching {
            backend.summarise(
                systemPrompt = SystemPrompt.DIGEST,
                rawHistoryText = rawHistoryText,
                modelName = modelName,
            )
        }

    companion object {
        /**
         * Convert any error we see while talking to a provider into a short,
         * kid-friendly sentence. Hides JSON / SDK noise like the Google SDK's
         * `MissingFieldException` on a 500, OpenRouter HTTP errors, and the
         * deliberate `UnsupportedOperationException` thrown by the
         * not-yet-ready local backend.
         */
        fun friendlyError(throwable: Throwable, debug: Boolean = false): String {
            val msg = (throwable.message ?: "").lowercase()
            val cls = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "UnknownError"

            // Always surface the real exception in logcat AND in the in-app
            // DebugLog ring buffer so devs can inspect failures from Settings
            // without needing to be plugged in to adb.
            Log.e("LunaAI", "Provider call failed: $cls", throwable)
            DebugLog.e(
                tag = "LunaAI",
                message = "$cls: ${throwable.message ?: "(no message)"}",
                throwable = throwable,
            )

            // In developer mode we hand back the raw error text so the chat
            // bubble itself acts as a debug surface.
            if (debug) {
                return buildString {
                    append("[DEBUG] ")
                    append(cls)
                    append(": ")
                    append(throwable.message ?: "(no message)")
                }
            }

            return when {
                throwable is UnsupportedOperationException &&
                    !throwable.message.isNullOrBlank() ->
                    throwable.message!!

                msg.contains("not found") ||
                    msg.contains("is not supported") ||
                    msg.contains("does not exist") ||
                    msg.contains("openrouter 404") ||
                    msg.contains("\"code\": 404") ||
                    msg.contains(" 404 ") ->
                    "I can't find that Gemma 4 model on this provider. Open Settings and pick a different model (or switch providers)."

                cls.contains("ResponseStoppedException", ignoreCase = true) ||
                    msg.contains("max_tokens") ||
                    msg.contains("max tokens") ||
                    msg.contains("content generation stopped") ->
                    "My answer got too long for one bubble. Try a shorter question, or ask me to keep it brief!"

                cls.contains("MissingFieldException", ignoreCase = true) ||
                    cls.contains("SerializationException", ignoreCase = true) ||
                    msg.contains("missingfieldexception") ||
                    msg.contains("\"status\": \"internal\"") ||
                    msg.contains("internal error encountered") ||
                    msg.contains("\"code\": 500") ||
                    msg.contains("\"code\": 503") ||
                    msg.contains("openrouter 500") ||
                    msg.contains("openrouter 502") ||
                    msg.contains("openrouter 503") ||
                    msg.contains("unavailable") ->
                    "Oh no — my brain is taking a quick break. Let's try that question again in a moment! 🌙"

                msg.contains("api key") ||
                    msg.contains("api_key") ||
                    msg.contains("permission_denied") ||
                    msg.contains("unauthenticated") ||
                    msg.contains("invalid_api_key") ||
                    msg.contains("openrouter 401") ||
                    msg.contains("openrouter 403") ||
                    msg.contains(" 401 ") ||
                    msg.contains(" 403 ") ->
                    "I can't reach my brain right now. A grown-up may need to check the API key in Settings."

                msg.contains("quota") ||
                    msg.contains("rate limit") ||
                    msg.contains("rate-limit") ||
                    msg.contains("resource_exhausted") ||
                    msg.contains("openrouter 429") ||
                    msg.contains(" 429 ") ->
                    "Wow, so many questions today! Let's wait a minute and try again."

                msg.contains("unable to resolve host") ||
                    msg.contains("failed to connect") ||
                    msg.contains("timeout") ||
                    msg.contains("timed out") ||
                    msg.contains("network") ->
                    "I can't get online right now. Check your internet and try again!"

                msg.contains("safety") || msg.contains("blocked") ->
                    "That one's tricky for me to answer safely. Let's try a different question!"

                else ->
                    "Sorry, I couldn't answer that just now. Please try again in a moment. " +
                        "(A grown-up can check Logcat tag 'LunaAI' for details.)"
            }
        }
    }
}

/**
 * Clean the model's raw text before showing it to a child. Four jobs:
 *  1. If the model emitted a "Final Polish:" / "Final Answer:" / "Answer:"
 *     anchor on its own line, keep only what comes after the last anchor —
 *     everything before it is drafting/planning chatter.
 *  2. Drop prose-style chain-of-thought paragraphs that reference "the
 *     prompt", talk about drafting/polishing/self-correction, or use
 *     planning verbs like "I'll treat the question as…", "I will
 *     revise…", "Let me polish…".
 *  3. Drop bullet/label-style leaks — Gemma sometimes echoes the system
 *     prompt's structure as "* Intent: …" or "* Tone check: …".
 *  4. Strip the most common markdown emphasis so kids see plain prose.
 */
private fun cleanLunaReply(raw: String): String {
    val metaKeywords = "(?:" +
        "user\\s*says|intent|target\\s*audience|persona|" +
        "direct\\s*answer|tone\\s*check|safety\\s*check|" +
        "greeting|content|length|format|output\\s*format|" +
        "system\\s*role|core\\s*rules?|notes?|plan|draft|drafting|" +
        "rules?\\s*to\\s*follow|step\\s*\\d+|" +
        "self[\\s-]*correction|polish(?:ing)?|reasoning|" +
        "thought\\s*process|chain[\\s-]*of[\\s-]*thought|thinking|" +
        "my\\s*(?:plan|thinking|reasoning|approach|draft)" +
        ")"

    val bulletMeta = Regex(
        "^\\s*[*\\-]\\s+\\*?\\*?$metaKeywords\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    val labelMeta = Regex(
        "^\\s*\\*?\\*?$metaKeywords\\*?\\*?\\s*:.*$",
        RegexOption.IGNORE_CASE,
    )
    val bulletPrefix = Regex("^\\s*[*\\-]\\s+")

    // A "section header" line: a line that is JUST a label ending in a colon,
    // signalling the actual answer follows. Anything before it is planning.
    // We deliberately don't match "Self-Correction:" here — that header is
    // followed by reasoning, not the answer; it's handled by the prose
    // filter below.
    val finalAnchorLine = Regex(
        "(?im)^\\s*\\*{0,2}\\s*" +
            "(?:final(?:\\s+(?:polish|answer|response|reply|draft|version))?" +
            "|polished(?:\\s+(?:answer|reply|response))?" +
            "|the\\s+answer|answer|response|reply)" +
            "\\s*\\*{0,2}\\s*:\\s*$",
    )

    // Prose chain-of-thought cues. Any paragraph containing one of these is
    // the model narrating its own process, not answering the child.
    val proseMeta = Regex(
        "(?i)(?:" +
            "\\bthe\\s+(?:system\\s+)?prompt\\s+(?:says|states|requires|asks|wants|implies|expects|specifies)\\b" +
            "|\\bself[\\s-]*correction\\b" +
            "|\\bfinal\\s+polish\\b" +
            "|\\bdrafting\\s+(?:the|my|a|this)\\b" +
            // Meta verbs only — "think" is intentionally excluded so that
            // benign phrases like "Let me think of a fun example!" don't
            // get scrubbed out of legitimate answers.
            "|\\b(?:let\\s+me|i'?ll|i\\s+will|i\\s+should)\\s+" +
            "(?:plan|draft|rewrite|revise|polish|reconsider|" +
            "interpret|approach\\s+this|treat\\s+(?:the|this|that)\\s+" +
            "(?:question|prompt|request|sentence|paragraph))\\b" +
            "|\\bhere'?s\\s+my\\s+(?:draft|attempt|plan|response\\s+plan|reasoning)\\b" +
            ")",
    )

    val isMetaLine: (String) -> Boolean = { t ->
        bulletMeta.containsMatchIn(t) || labelMeta.containsMatchIn(t)
    }

    // Step 1: if a "Final Polish:" / "Answer:" / etc. anchor exists on its
    // own line, throw away everything up to and including the LAST one.
    val afterAnchor = finalAnchorLine.findAll(raw).lastOrNull()?.let { match ->
        raw.substring(match.range.last + 1)
    } ?: raw

    // Step 2: split into paragraphs (blank-line separated) and drop the ones
    // that are clearly the model narrating its own process.
    val paragraphs = afterAnchor.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val keptParagraphs = paragraphs.filter { para ->
        if (proseMeta.containsMatchIn(para)) return@filter false
        val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Paragraph made up entirely of bullet/label meta lines? Drop it.
        if (lines.isNotEmpty() && lines.all { isMetaLine(it) }) return@filter false
        true
    }

    // Step 3: line-level scrub for leaks mixed into a kept paragraph (the
    // old behaviour, kept as a safety net).
    val finalLines = keptParagraphs.joinToString("\n\n").lines()
    val leakDetected = finalLines.any {
        it.trim().let { t -> t.isNotEmpty() && isMetaLine(t) }
    }
    val keptLines = finalLines.filterNot { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> false
            isMetaLine(t) -> true
            leakDetected && bulletPrefix.containsMatchIn(t) -> true
            else -> false
        }
    }

    val cleaned = keptLines
        .joinToString("\n")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+?)\\*(?!\\*)"), "$1")
        .replace(bulletPrefix, "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    return cleaned.ifBlank {
        "Hmm, let me think about that another way — could you ask me again?"
    }
}
