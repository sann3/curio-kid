package com.curiokid.app.ai.local

/**
 * Static catalog of Gemma `.task` files Curio Kid knows how to download
 * and run on-device through MediaPipe LLM Inference.
 *
 * The list is intentionally tiny — bigger models won't fit into RAM on the
 * cheap hand-me-down tablet a kid actually gets to use, and only multimodal
 * variants are shipped because the rest of the app (camera, image attach)
 * assumes Luna can see pictures regardless of which provider is selected.
 *
 * ## Branding vs underlying weights
 *
 * Real Gemma 4 LiteRT `.task` builds aren't published yet (as of 2026 the
 * `litert-community/gemma-4-*` repos don't exist), so the URLs below point
 * at Google's **Gemma 3n** multimodal `.task` preview repos — the only
 * vision-capable Gemma family that ships as a MediaPipe-ready
 * `litert-preview` artifact today. The user-facing display name keeps the
 * "Gemma 4" branding the rest of the app uses; swap the [displayName] when
 * real Gemma 4 `.task` files are published, then update [url] / [sizeBytes].
 *
 * ## Gated downloads
 *
 * Both Gemma 3n repos are gated — visit each model's page on huggingface.co
 * once, click **Acknowledge license**, then paste a `hf_…` access token
 * into Settings → On-device → HuggingFace token. [LocalModelManager] sends
 * it as `Authorization: Bearer` on the download.
 */
object LocalGemmaCatalog {

    data class Variant(
        /** Stable identifier persisted in EncryptedSharedPreferences. */
        val id: String,
        /** Human-readable name shown in the Settings model picker. */
        val displayName: String,
        /** Expected `.task` file size in bytes — drives the progress bar and free-space check. */
        val sizeBytes: Long,
        /** Lower-case hex SHA-256 of the `.task` file. Verified after download. Empty string skips the check. */
        val sha256: String,
        /** Direct HTTPS URL to the `.task` file (must support HTTP `Range` requests for resumable downloads). */
        val url: String,
        /** True when the variant accepts image input through MediaPipe's `addImage` session API. */
        val supportsVision: Boolean,
    )

    val VARIANT_2B_VISION: Variant = Variant(
        id = "gemma-4-2b-it-int4-vision",
        displayName = "Gemma 4 · 2B vision (int4)",
        // Real size of the underlying gemma-3n-E2B-it-int4.task: 3,136,226,711 bytes.
        sizeBytes = 3_136_226_711L,
        // Left blank until the upstream repo publishes a stable checksum.
        // The download still succeeds; we just skip the post-download
        // integrity verification step.
        sha256 = "",
        url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
        supportsVision = true,
    )

    val VARIANT_7B_VISION: Variant = Variant(
        id = "gemma-4-7b-it-int4-vision",
        displayName = "Gemma 4 · 7B vision (int4)",
        // Real size of the underlying gemma-3n-E4B-it-int4.task: 4,405,655,031 bytes.
        sizeBytes = 4_405_655_031L,
        sha256 = "",
        url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
        supportsVision = true,
    )

    val ALL: List<Variant> = listOf(VARIANT_2B_VISION, VARIANT_7B_VISION)

    val DEFAULT: Variant = VARIANT_2B_VISION

    fun byId(id: String?): Variant? = ALL.firstOrNull { it.id == id }
}
