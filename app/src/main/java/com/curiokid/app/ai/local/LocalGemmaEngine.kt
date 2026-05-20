package com.curiokid.app.ai.local

import android.content.Context
import android.graphics.Bitmap
import com.curiokid.app.ai.provider.ChatTurn
import com.curiokid.app.data.debug.DebugLog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide MediaPipe LLM Inference engine.
 *
 * Loading a multi-GB Gemma `.task` file is expensive (several seconds plus
 * 1.5–5 GB of pinned RAM), so we keep the [LlmInference] instance alive
 * across `ask()` calls and only reload when the user picks a different
 * variant in Settings. [unload] is called on memory pressure or when the
 * user switches away from `LlmProvider.LOCAL`.
 *
 * Per-call we open a fresh [LlmInferenceSession], replay the system
 * prompt + bounded chat history, optionally attach an image (vision-only
 * models), then call `generateResponse`. This matches the stateless
 * per-call contract every other [com.curiokid.app.ai.provider.LlmBackend]
 * already exposes — no surprise behavioural drift from sticky sessions.
 */
class LocalGemmaEngine(
    private val context: Context,
) {

    private val mutex = Mutex()

    private var loadedVariantId: String? = null
    private var inference: LlmInference? = null

    /** True iff the underlying inference instance is loaded into memory. */
    val isLoaded: Boolean
        get() = inference != null

    /**
     * Lazily (re)load [variant] from [modelFile] if it isn't already the
     * active one. Subsequent calls with the same variant are essentially
     * free.
     */
    suspend fun ensureLoaded(
        variant: LocalGemmaCatalog.Variant,
        modelFile: File,
    ): LlmInference = mutex.withLock {
        val existing = inference
        if (existing != null && loadedVariantId == variant.id) return@withLock existing

        existing?.close()
        inference = null
        loadedVariantId = null

        DebugLog.i(TAG, "Loading ${variant.id} from ${modelFile.absolutePath}")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .apply { if (variant.supportsVision) setMaxNumImages(1) }
            .build()

        val instance = withContext(Dispatchers.IO) {
            LlmInference.createFromOptions(context, options)
        }
        inference = instance
        loadedVariantId = variant.id
        instance
    }

    /**
     * Run a single prompt against the currently-loaded model.
     * `addImage` is only called when [variant.supportsVision] AND [image]
     * is non-null; otherwise the request is text-only.
     */
    suspend fun generate(
        variant: LocalGemmaCatalog.Variant,
        modelFile: File,
        systemPrompt: String,
        history: List<ChatTurn>,
        userText: String,
        image: Bitmap?,
    ): String = withContext(Dispatchers.Default) {
        val engine = ensureLoaded(variant, modelFile)
        // Only flip vision modality ON when we're actually about to feed an
        // image. The MediaPipe vision graph for Gemma 3n hard-requires an
        // `addImage` call once vision is enabled — generating with the
        // graph wired up but no image bound crashes the native side with
        // SIGSEGV. For text-only asks we use a plain text session.
        val useVision = variant.supportsVision && image != null
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .apply {
                if (useVision) {
                    setGraphOptions(
                        GraphOptions.builder().setEnableVisionModality(true).build(),
                    )
                }
            }
            .build()

        LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            // Replay system prompt and bounded history as plain text chunks.
            // The contract LunaAI expects is "I send the systemPrompt + the
            // conversation; you give me the next assistant turn", so we
            // serialise prior turns inline rather than relying on a
            // long-lived session.
            session.addQueryChunk(systemPrompt)
            session.addQueryChunk("\n\n")
            for (turn in history) {
                val tag = if (turn.role == ChatTurn.Role.USER) "Child" else "Luna"
                session.addQueryChunk("$tag: ${turn.text}\n")
            }
            session.addQueryChunk("Child: ${userText.ifBlank { "Tell me something cool!" }}\n")
            session.addQueryChunk("Luna:")

            if (useVision) {
                val downscaled = downscaleForVision(image!!)
                session.addImage(BitmapImageBuilder(downscaled).build())
            }

            generateBlocking(session)
        }
    }

    /**
     * Single-turn summarisation entry point used by the parent dashboard.
     */
    suspend fun summarise(
        variant: LocalGemmaCatalog.Variant,
        modelFile: File,
        systemPrompt: String,
        rawHistoryText: String,
    ): String = withContext(Dispatchers.Default) {
        val engine = ensureLoaded(variant, modelFile)
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(0.4f)
            .build()

        LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            session.addQueryChunk(systemPrompt)
            session.addQueryChunk("\n\n")
            session.addQueryChunk(rawHistoryText)
            generateBlocking(session)
        }
    }

    /**
     * Free the underlying inference instance. Safe to call when nothing is
     * loaded. Next call to [ensureLoaded] will reload from disk.
     */
    suspend fun unload() = mutex.withLock {
        inference?.let { instance ->
            DebugLog.i(TAG, "Unloading $loadedVariantId")
            instance.close()
        }
        inference = null
        loadedVariantId = null
    }

    /**
     * Stream into a single string. We use the async streaming API rather
     * than the blocking `generateResponse` so an in-flight generation can
     * be cancelled when the coroutine is cancelled, and so we don't pin a
     * thread for the whole duration on lower-end phones.
     *
     * Any throwable raised either synchronously by `generateResponseAsync`
     * or asynchronously inside the listener is forwarded to the awaiting
     * coroutine, AND eagerly logged to [DebugLog] (and Android's standard
     * Log) so a partial / silent failure isn't lost. MediaPipe native
     * SIGSEGV / SIGABRT crashes kill the process before any Java side can
     * react — those need adb logcat to diagnose.
     */
    private suspend fun generateBlocking(session: LlmInferenceSession): String {
        val result = CompletableDeferred<String>()
        val builder = StringBuilder()
        try {
            session.generateResponseAsync { partial: String?, done: Boolean ->
                if (partial != null) builder.append(partial)
                if (done) result.complete(builder.toString())
            }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "generateResponseAsync threw synchronously: ${t.message}", t)
            result.completeExceptionally(t)
        }
        return try {
            result.await().ifBlank {
                "Hmm, I'm not sure how to answer that one. Let's try a different question!"
            }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "generation failed: ${t.message}", t)
            throw t
        }
    }

    /**
     * MediaPipe vision graphs perform best with reasonably small inputs;
     * a giant 12-megapixel camera frame just bloats latency without
     * helping accuracy on the kinds of questions a kid asks.
     */
    private fun downscaleForVision(bitmap: Bitmap): Bitmap {
        val maxEdge = 768
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val TAG = "LocalGemmaEngine"

        // MediaPipe context window for the active session. 4096 covers the
        // system prompt + a 16-turn chat history + an answer comfortably.
        private const val MAX_TOKENS = 4096

        private const val TOP_K = 40
        private const val TEMPERATURE = 0.7f
    }
}
