package com.curiokid.app.ai

import android.graphics.Bitmap
import android.util.Log
import com.curiokid.app.ai.local.LocalGemmaEngine
import com.curiokid.app.ai.local.LocalModelManager
import com.curiokid.app.ai.provider.ChatTurn
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
 *
 * `localEngine` and `localManager` are only consulted when [provider] is
 * [LlmProvider.LOCAL]; for cloud providers they are deliberately ignored
 * so callers don't have to construct them just to talk to Google.
 */
class LunaAI(
    private val provider: LlmProvider,
    private val apiKey: String?,
    private val modelName: String,
    private val kidAge: Int,
    private val localEngine: LocalGemmaEngine? = null,
    private val localManager: LocalModelManager? = null,
) {

    private val backend: LlmBackend = when (provider) {
        LlmProvider.GOOGLE_AI_STUDIO -> GoogleAiStudioBackend(apiKey.orEmpty())
        LlmProvider.OPEN_ROUTER -> OpenRouterBackend(apiKey.orEmpty())
        LlmProvider.LOCAL -> {
            val engine = localEngine
                ?: error("LocalGemmaEngine is required when provider is LOCAL")
            val manager = localManager
                ?: error("LocalModelManager is required when provider is LOCAL")
            LocalGemmaBackend(engine, manager)
        }
    }

    suspend fun ask(
        question: String,
        image: Bitmap? = null,
        history: List<ChatTurn> = emptyList(),
    ): Result<String> = runCatching {
        val raw = backend.ask(
            systemPrompt = SystemPrompt.luna(kidAge),
            history = history,
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
            // The Google AI SDK wraps lower-level failures in
            // `UnknownException("Something unexpected happened.")`, hiding
            // the real diagnostic (e.g. SocketTimeoutException) one or two
            // causes down. Walk the chain so our keyword matches see the
            // actual failure, not just the wrapper.
            val chainText = buildString {
                var t: Throwable? = throwable
                val seen = mutableSetOf<Throwable>()
                while (t != null && seen.add(t)) {
                    append(t::class.qualifiedName ?: t::class.simpleName ?: "?")
                    append(": ")
                    append(t.message ?: "")
                    append('\n')
                    t = t.cause
                }
            }
            val msg = chainText.lowercase()
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

                // On-device specific failures. MediaPipe surfaces native
                // load problems as IllegalStateException / RuntimeException
                // with characteristic substrings, and OOM as the standard
                // OutOfMemoryError; we map all of them into one short,
                // kid-readable sentence per failure mode.
                throwable is OutOfMemoryError ||
                    msg.contains("out of memory") ||
                    msg.contains("oom") ||
                    msg.contains("could not allocate") ||
                    msg.contains("failed to allocate") ->
                    "This phone doesn't have enough memory to run Gemma 4 on-device right now. " +
                        "Try the smaller variant in Settings, or switch to Google AI Studio."

                msg.contains("model file") ||
                    msg.contains("modelpath") ||
                    msg.contains("failed to load model") ||
                    msg.contains("failed to initialize") ||
                    msg.contains("llminference") ||
                    msg.contains("mediapipe") ->
                    "Luna's offline brain didn't start up. Open Settings → On-device " +
                        "Gemma 4 to re-download the model, or switch to Google AI Studio."

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

                // Read/socket timeout: we DID reach the server, the
                // model just took longer than the SDK's hard-coded 80s
                // socket timeout to start replying. Misclassifying this
                // as "no internet" sends parents on a wild goose chase.
                msg.contains("sockettimeoutexception") ||
                    msg.contains("socket timeout") ||
                    msg.contains("read timed out") ||
                    msg.contains("timed out") ||
                    msg.contains("timeout") ->
                    "That question got my brain thinking really hard — it took too long to answer. Try a shorter question, or ask me again in a moment! 🌙"

                msg.contains("unable to resolve host") ||
                    msg.contains("failed to connect") ||
                    msg.contains("no address associated") ||
                    msg.contains("network is unreachable") ||
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
 *  2. Walk paragraphs in order. Drop leading meta paragraphs (CoT before
 *     the answer), but once we've kept at least one answer paragraph,
 *     truncate the moment a meta paragraph appears — Gemma loves to
 *     append a post-hoc "Check sentence count: 1. … 2. …" / "Wait, I
 *     should check if X needs definition" / "Let's try a 3-sentence
 *     version just to be safe" block after a perfectly good answer, and
 *     everything from that point on is reasoning, not answer.
 *  3. Drop bullet/label-style leaks — Gemma sometimes echoes the system
 *     prompt's structure as "* Intent: …" or "* Tone check: …".
 *  4. Strip the most common markdown emphasis so kids see plain prose.
 */
private fun cleanLunaReply(raw: String): String {
    val metaKeywords = "(?:" +
        "user\\s*says|intent|target\\s*audience|persona|" +
        "direct\\s*answer|tone\\s*check|safety\\s*check|" +
        "sentence\\s*count(?:\\s*check)?|word\\s*count|" +
        "check\\s+sentence\\s*count|" +
        "greeting|content|length|format|output\\s*format|" +
        "system\\s*role|core\\s*rules?|notes?|plan|draft|drafting|" +
        "rules?\\s*to\\s*follow|step\\s*\\d+|" +
        "self[\\s-]*correction|polish(?:ing)?|reasoning|" +
        "thought\\s*process|chain[\\s-]*of[\\s-]*thought|thinking|" +
        "verification|verify|" +
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
            "(?:final(?:\\s+(?:polish|answer|response|reply|draft|version" +
            "|selection|output|result|choice))?" +
            "|polished(?:\\s+(?:answer|reply|response|version))?" +
            "|revised(?:\\s+(?:draft|answer|reply|response|version))?" +
            "|let'?s\\s+go\\s+with(?:\\s+this)?" +
            "|going\\s+with(?:\\s+this)?" +
            "|here'?s\\s+(?:the|my)\\s+(?:final\\s+)?(?:answer|reply|response)" +
            "|my\\s+(?:final|polished|revised)\\s+(?:answer|reply|response|version)" +
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
            // Post-hoc verification / alternate-draft tics Gemma loves
            // to emit AFTER an otherwise clean answer.
            "|\\bsentence\\s+count\\b" +
            "|\\bword\\s+count\\b" +
            "|\\b(?:just\\s+)?to\\s+be\\s+safe\\b" +
            "|\\bone\\s+more\\s+check\\b" +
            "|\\bneeds?\\s+(?:a\\s+)?definition\\b" +
            // Per-word "hard word" self-audit Gemma sometimes appends:
            //   Wait, is "atmosphere" a "hard word"? Yes.
            //   Definition is provided.
            "|\\b(?:hard|tricky|difficult|big|tough|complex)\\s+word\\b" +
            "|\\bdefinitions?\\s+(?:is|are|was|were)\\s+(?:already\\s+)?provided\\b" +
            "|\\bdefinitions?\\s+(?:already\\s+)?provided\\b" +
            "|\\bi'?ll\\s+stick\\s+(?:to|with)\\b" +
            "|\\bsticking\\s+(?:to|with)\\s+(?:the|my|that|this)\\b" +
            "|\\blet'?s\\s+try\\s+(?:a|the|another|one\\s+more)\\s+" +
            "(?:\\w+(?:[-\\s]\\w+){0,3}\\s+)?" +
            "(?:version|draft|attempt|wording|phrasing|sentence)\\b" +
            // Narrow "Wait, …" self-correction starter. We don't match a
            // bare "Wait, I" because phrases like "Wait, I love that!" are
            // perfectly legitimate; only flag a few obviously-meta tails:
            // an "I should/need to/…" pursuit, a reference to the prompt,
            // a "let me revise/…", or a self-question that immediately
            // quotes a word being audited (`Wait, is "atmosphere"…`).
            "|^\\s*wait[,!.]\\s+" +
            "(?:i\\s+(?:should|need\\s+to|have\\s+to|must|forgot|missed)" +
            "|the\\s+(?:system\\s+)?prompt" +
            "|let\\s+me\\s+(?:revise|rewrite|polish|recheck|reconsider|" +
            "check|count|verify|try\\s+again)" +
            "|(?:is|are|does|do|did|was|were|should)\\s+[\"'\u201C\u2018])\\b" +
            // Meta verbs only — "think" is intentionally excluded so that
            // benign phrases like "Let me think of a fun example!" don't
            // get scrubbed out of legitimate answers.
            "|\\b(?:let\\s+me|i'?ll|i\\s+will|i\\s+should)\\s+" +
            "(?:plan|draft|rewrite|revise|polish|reconsider|" +
            "check|recheck|verify|count|" +
            "interpret|approach\\s+this|treat\\s+(?:the|this|that)\\s+" +
            "(?:question|prompt|request|sentence|paragraph))\\b" +
            "|\\bhere'?s\\s+my\\s+(?:draft|attempt|plan|response\\s+plan|reasoning)\\b" +
            ")",
    )

    val isMetaLine: (String) -> Boolean = { t ->
        bulletMeta.containsMatchIn(t) || labelMeta.containsMatchIn(t)
    }

    val isMetaParagraph: (String) -> Boolean = { para ->
        if (proseMeta.containsMatchIn(para)) {
            true
        } else {
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            lines.isNotEmpty() && lines.all { isMetaLine(it) }
        }
    }

    // Step 1: if a "Final Polish:" / "Answer:" / etc. anchor exists on its
    // own line, throw away everything up to and including the LAST one.
    val afterAnchor = finalAnchorLine.findAll(raw).lastOrNull()?.let { match ->
        raw.substring(match.range.last + 1)
    } ?: raw

    // Step 2: split into paragraphs (blank-line separated) and walk them
    // in order. Skip leading meta paragraphs; once we've kept an answer
    // paragraph, the first meta paragraph after it terminates the answer
    // (everything from there on is post-hoc reasoning / alternative drafts).
    val paragraphs = afterAnchor.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val keptParagraphs = buildList {
        var seenAnswer = false
        for (para in paragraphs) {
            if (isMetaParagraph(para)) {
                if (seenAnswer) break
                // Leading meta before any answer — drop it and keep looking.
                continue
            }
            add(para)
            seenAnswer = true
        }
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

    return dedupeDoubledTail(cleaned).ifBlank {
        "Hmm, let me think about that another way — could you ask me again?"
    }
}

/**
 * Some models (notably Gemma) sometimes emit the same final answer back-to-back,
 * with no separator between the two copies — e.g. "…cats make? 🐾…cats make? 🐾".
 * If the cleaned string is essentially two identical halves stitched together,
 * keep just one copy.
 */
private fun dedupeDoubledTail(text: String): String {
    val trimmed = text.trim()
    val n = trimmed.length
    if (n < 40) return trimmed

    // The split point isn't always exactly n/2 (one half may have trailing
    // whitespace or a stray newline), so probe a small window around it.
    val approxMid = n / 2
    val window = (approxMid - 5).coerceAtLeast(1)..(approxMid + 5).coerceAtMost(n - 1)
    for (mid in window) {
        val first = trimmed.substring(0, mid).trim()
        val second = trimmed.substring(mid).trim()
        if (first.length >= 20 && first == second) return first
    }
    return trimmed
}
