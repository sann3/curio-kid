package com.curiokid.app.ai.local

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import com.curiokid.app.data.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the lifecycle of locally-installed Gemma 4 `.task` files.
 *
 * Responsibilities:
 *  - Tell the rest of the app whether a given variant is installed, in
 *    flight, or missing (`stateFor` returns a `StateFlow`).
 *  - Stream-download a `.task` file from its catalog URL into app-private
 *    internal storage with HTTP `Range`-based resume, free-space pre-flight,
 *    metered-network awareness, optional sha256 verification, and
 *    cancellation.
 *  - Resolve a variant id into a `File` for the inference engine.
 *  - Delete a previously-installed variant ("Free up space").
 *
 * Multi-GB downloads are deliberately scoped to `applicationScope` (a
 * supervisor coroutine on the singleton `CurioKidApplication`) instead of
 * a `WorkManager` foreground service — the "phase 1" UX accepts that
 * killing the app cancels the download. Resume support means the user
 * just re-taps Download next time.
 */
class LocalModelManager(
    context: Context,
    /**
     * Resolves the user's HuggingFace access token at download time (kept
     * as a lambda so the manager doesn't have to depend on `SettingsManager`
     * directly, and so token rotations apply to the next download without
     * re-creating the manager). Return `null` when the user hasn't
     * provided one yet — ungated mirrors will still work.
     */
    private val tokenProvider: () -> String? = { null },
) {

    private val appContext: Context = context.applicationContext

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    /**
     * One [MutableStateFlow] per variant id. Lazily created on first use so
     * a fresh install surfaces `NotInstalled` immediately without scanning
     * disk for variants the user has never picked.
     */
    private val states = mutableMapOf<String, MutableStateFlow<LocalModelState>>()

    private val activeJobs = mutableMapOf<String, Job>()

    /** Hot stream of install state for a given variant. */
    fun stateFor(variant: LocalGemmaCatalog.Variant): StateFlow<LocalModelState> =
        stateFlow(variant).asStateFlow()

    /**
     * Synchronous lookup used by [LocalGemmaEngine] right before loading.
     * Returns `null` when the file isn't on disk.
     */
    fun installedFile(variant: LocalGemmaCatalog.Variant): File? =
        modelFile(variant).takeIf { it.exists() && it.length() > 0L }

    /**
     * Returns true when running on a metered network (cellular, hotspot).
     * The Settings UI uses this to surface a "this is a 1.6 GB download
     * on cellular" confirmation before kicking off [download].
     */
    fun isOnMeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.isActiveNetworkMetered ?: false
    }

    /**
     * Kick off a resumable download for [variant]. Safe to call twice in a
     * row — the second call no-ops while the first is still active. The
     * returned [Job] can be cancelled to abort cleanly (the partial file
     * is left on disk so the next [download] resumes from where it
     * stopped).
     */
    fun download(variant: LocalGemmaCatalog.Variant): Job {
        val state = stateFlow(variant)
        synchronized(activeJobs) {
            activeJobs[variant.id]?.let { existing ->
                if (existing.isActive) return existing
            }
            val job = applicationScope.launch {
                try {
                    runDownload(variant, state)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    state.value = LocalModelState.NotInstalled
                    throw cancel
                } catch (t: Throwable) {
                    DebugLog.e(TAG, "download(${variant.id}) failed: ${t.message}", t)
                    state.value = LocalModelState.Failed(
                        reason = t.message ?: t::class.simpleName ?: "Download failed",
                    )
                }
            }
            activeJobs[variant.id] = job
            job.invokeOnCompletion {
                synchronized(activeJobs) {
                    if (activeJobs[variant.id] === job) activeJobs.remove(variant.id)
                }
            }
            return job
        }
    }

    fun cancel(variant: LocalGemmaCatalog.Variant) {
        synchronized(activeJobs) {
            activeJobs[variant.id]?.cancel()
            activeJobs.remove(variant.id)
        }
    }

    /**
     * Delete the on-disk `.task` file for [variant] (and any partial
     * `.task.part` left behind by an aborted download).
     */
    suspend fun delete(variant: LocalGemmaCatalog.Variant) = mutex.withLock {
        cancel(variant)
        withContext(Dispatchers.IO) {
            modelFile(variant).delete()
            partialFile(variant).delete()
        }
        stateFlow(variant).value = LocalModelState.NotInstalled
    }

    private suspend fun runDownload(
        variant: LocalGemmaCatalog.Variant,
        state: MutableStateFlow<LocalModelState>,
    ) = withContext(Dispatchers.IO) {
        val target = modelFile(variant)
        if (target.exists() && target.length() > 0L) {
            // Already installed — we still emit Installed so the UI updates if it
            // somehow drifted out of sync with disk reality.
            state.value = LocalModelState.Installed(target)
            return@withContext
        }

        val partial = partialFile(variant)
        val resumeFromBytes = if (partial.exists()) partial.length() else 0L

        // Free-space pre-check. We need the *remaining* bytes plus a 20 %
        // buffer for the rename and OS overhead.
        val remaining = (variant.sizeBytes - resumeFromBytes).coerceAtLeast(0L)
        val needed = (remaining * 12) / 10
        val available = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (available < needed) {
            throw IOException(
                "Need ${humanBytes(needed)} free, only ${humanBytes(available)} available."
            )
        }

        partial.parentFile?.mkdirs()

        state.value = LocalModelState.Downloading(
            bytesDownloaded = resumeFromBytes,
            totalBytes = variant.sizeBytes,
        )

        val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val connection = (URL(variant.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (resumeFromBytes > 0L) {
                setRequestProperty("Range", "bytes=$resumeFromBytes-")
            }
            // Gated HuggingFace repos (e.g. Gemma) require a Bearer token.
            // Sent on every request including redirects to the LFS CDN —
            // HuggingFace LFS preserves the Authorization header on its
            // own redirects, so this works end-to-end.
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("User-Agent", "CurioKid/1.0 (Android)")
        }

        val effectiveTotal: Long
        val startOffset: Long
        try {
            connection.connect()
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> {
                    startOffset = resumeFromBytes
                    effectiveTotal = parseTotalFromContentRange(connection)
                        ?: (resumeFromBytes + connection.contentLengthLong.coerceAtLeast(0L))
                }
                code == HttpURLConnection.HTTP_OK -> {
                    // Server ignored Range — start from scratch.
                    startOffset = 0L
                    if (resumeFromBytes > 0L) partial.delete()
                    effectiveTotal = connection.contentLengthLong.takeIf { it > 0L } ?: variant.sizeBytes
                }
                else -> {
                    val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                    val hint = when (code) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> {
                            if (token.isNullOrBlank()) {
                                " — this file is gated. Open Settings → On-device → HuggingFace token and paste your `hf_…` token."
                            } else {
                                " — your HuggingFace token was rejected. Double-check it in Settings and make sure you've accepted the model's license at huggingface.co."
                            }
                        }
                        HttpURLConnection.HTTP_FORBIDDEN ->
                            " — your HuggingFace account doesn't have access to this model yet. Visit the model page on huggingface.co and click \"Accept license\"."
                        HttpURLConnection.HTTP_NOT_FOUND ->
                            " — that URL no longer exists. Check the URL in LocalGemmaCatalog.kt."
                        else -> ""
                    }
                    throw IOException("HTTP $code from ${variant.url}: ${errBody.ifBlank { "request failed" }}$hint")
                }
            }

            RandomAccessFile(partial, "rw").use { raf ->
                raf.seek(startOffset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = startOffset
                    var lastEmittedPercent = -1
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        val percent = if (effectiveTotal > 0) ((written * 100L) / effectiveTotal).toInt() else -1
                        if (percent != lastEmittedPercent) {
                            lastEmittedPercent = percent
                            state.value = LocalModelState.Downloading(
                                bytesDownloaded = written,
                                totalBytes = effectiveTotal,
                            )
                        }
                    }
                }
            }
        } finally {
            runCatching { connection.disconnect() }
        }

        if (variant.sha256.isNotBlank()) {
            val actual = sha256(partial)
            if (!actual.equals(variant.sha256, ignoreCase = true)) {
                partial.delete()
                throw IOException(
                    "Downloaded file failed integrity check (expected ${variant.sha256.take(12)}…, got ${actual.take(12)}…)."
                )
            }
        }

        if (!partial.renameTo(target)) {
            // Cross-device rename can fail; fall back to copy-then-delete.
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }

        state.value = LocalModelState.Installed(target)
        DebugLog.i(TAG, "Installed ${variant.id} (${humanBytes(target.length())})")
    }

    private fun parseTotalFromContentRange(connection: HttpURLConnection): Long? {
        val range = connection.getHeaderField("Content-Range") ?: return null
        // Format: "bytes 200-1023/1024"
        val slash = range.lastIndexOf('/')
        if (slash < 0) return null
        return range.substring(slash + 1).toLongOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stateFlow(variant: LocalGemmaCatalog.Variant): MutableStateFlow<LocalModelState> {
        synchronized(states) {
            return states.getOrPut(variant.id) {
                MutableStateFlow(initialState(variant))
            }
        }
    }

    private fun initialState(variant: LocalGemmaCatalog.Variant): LocalModelState {
        val file = modelFile(variant)
        return if (file.exists() && file.length() > 0L) {
            LocalModelState.Installed(file)
        } else {
            LocalModelState.NotInstalled
        }
    }

    private fun modelFile(variant: LocalGemmaCatalog.Variant): File =
        File(modelsDir(), "${variant.id}.task")

    private fun partialFile(variant: LocalGemmaCatalog.Variant): File =
        File(modelsDir(), "${variant.id}.task.part")

    private fun modelsDir(): File =
        File(appContext.filesDir, "models").apply { mkdirs() }

    companion object {
        private const val TAG = "LocalModelManager"

        fun humanBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.size - 1) {
                value /= 1024.0
                unitIndex++
            }
            return "%.1f %s".format(value, units[unitIndex])
        }
    }
}

/**
 * Snapshot of where a single Gemma 4 variant stands on this device.
 */
sealed interface LocalModelState {
    data object NotInstalled : LocalModelState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : LocalModelState {
        val percent: Int
            get() = if (totalBytes > 0L) ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    data class Installed(val file: File) : LocalModelState

    data class Failed(val reason: String) : LocalModelState
}
