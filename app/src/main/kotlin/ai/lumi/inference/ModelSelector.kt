package ai.lumi.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-detects device capabilities and assigns a [DeviceTier].
 * The user NEVER sees a model name, runtime, or configuration option.
 * Called once at first launch; result cached in DataStore via [ai.lumi.data.datastore.LumiPreferences].
 */
@Singleton
class ModelSelector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // Snapdragon SoC identifiers
        private const val SM8850 = "SM8850" // SD 8 Elite Gen 5 — iQOO 15
        private const val SM8750 = "SM8750" // SD 8 Elite Gen 4
        private const val SM8650 = "SM8650" // SD 8 Gen 3

        private const val FREE_GB_FLAGSHIP = 6.0
        private const val FREE_GB_MID_HIGH = 4.0
        private const val FREE_GB_BUDGET = 2.0
    }

    fun configure(): DeviceTier {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: "unknown"
        } else {
            Build.HARDWARE ?: "unknown"
        }
        val freeGb = getFreeRamGb()

        Timber.i("ModelSelector: SoC=$socModel, freeRAM=%.2f GB".format(freeGb))

        val tier = when {
            socModel.contains(SM8850, ignoreCase = true) && freeGb >= FREE_GB_FLAGSHIP ->
                DeviceTier.FLAGSHIP

            (socModel.contains(SM8750, ignoreCase = true) ||
                    socModel.contains(SM8650, ignoreCase = true)) && freeGb >= FREE_GB_MID_HIGH ->
                DeviceTier.MID_HIGH

            freeGb >= FREE_GB_BUDGET ->
                DeviceTier.BUDGET

            else ->
                DeviceTier.MINIMAL
        }

        Timber.i("ModelSelector: assigned tier=$tier")
        return tier
    }

    private fun getFreeRamGb(): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Returns the set of models that must be downloaded for [tier] and [languages].
     * Only includes models where [ModelSpec.isDirectDownloadable] = true.
     * Non-downloadable models (e.g. Qualcomm QAIRT bundles) use Cloud/Groq mode automatically.
     */
    fun requiredModels(tier: DeviceTier, languages: Set<String> = emptySet()): List<ModelSpec> = buildList {
        // Base multilingual/detector Whisper model is required for all active ASR tiers
        // because WhisperEngine requires it for initial language detection and transcription.
        when (tier) {
            DeviceTier.FLAGSHIP, DeviceTier.MID_HIGH ->
                add(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)
            DeviceTier.BUDGET ->
                add(ModelSpec.WHISPER_SMALL_Q4)
            DeviceTier.MINIMAL -> { /* no ASR */ }
        }

        // Additional language-specific fine-tuned models
        if (languages.contains("hi")) {
            add(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        }
        // VLM — QWEN3_VL_4B_QAIRT is a Qualcomm SDK model (isDirectDownloadable=false),
        // so it will be filtered out. Use Moondream2 as the on-device VLM where available.
        when (tier) {
            DeviceTier.FLAGSHIP, DeviceTier.MID_HIGH, DeviceTier.BUDGET ->
                add(ModelSpec.MOONDREAM2_Q4)
            DeviceTier.MINIMAL -> { /* no VLM */ }
        }
        // Memory LLM
        if (tier == DeviceTier.FLAGSHIP || tier == DeviceTier.MID_HIGH) {
            add(ModelSpec.QWEN3_1_7B_Q4)
        }
        // TTS — Kokoro bundled or System TTS. Nothing to download.
    }.filter { it.isDirectDownloadable }
}

/**
 * Specification for each downloadable model file.
 *
 * All [downloadUrl] values with [isDirectDownloadable]=true are confirmed public
 * direct-download links that do not require authentication tokens.
 *
 * [isDirectDownloadable] = false means the model is obtained via a vendor SDK or
 * sideload path — it is excluded from the download loop; the app falls back to
 * Cloud/Groq mode automatically for those capabilities.
 */
enum class ModelSpec(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    /** If false, this model cannot be HTTP-downloaded and will be skipped in the download loop. */
    val isDirectDownloadable: Boolean = true
) {
    WHISPER_HINDI_MEDIUM_Q4(
        displayName = "Hindi Speech Model",
        fileName = "whisper_hindi_medium_q4.bin",
        // ggml-org/whisper-medium Q4_0 — publicly accessible without a login token
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 515_000_000L
    ),
    WHISPER_LARGE_V3_TURBO_Q4(
        displayName = "English Speech Model",
        fileName = "whisper_large_v3_turbo_q4.bin",
        // ggml-org/whisper-large-v3-turbo Q4_0 — public release, no token required
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 834_000_000L
    ),
    WHISPER_SMALL_Q4(
        displayName = "Speech Model (Compact)",
        fileName = "whisper_small_q4.bin",
        // ggml-org/whisper-small Q4_0 — public
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 190_000_000L
    ),
    QWEN3_VL_4B_QAIRT(
        displayName = "Vision AI Model",
        fileName = "qwen3_vl_4b_instruct_qairt.zip",
        // Qualcomm AI Hub SDK model — NOT downloadable via HTTP.
        // Marked isDirectDownloadable=false → excluded from download loop.
        // App uses Groq cloud vision inference instead.
        downloadUrl = "https://aihub.qualcomm.com/models/qwen3_vl_4b_instruct",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 2_700_000_000L,
        isDirectDownloadable = false
    ),
    MOONDREAM2_Q4(
        displayName = "Screen Reading Model",
        fileName = "moondream2_q4.gguf",
        // vikhyatk/moondream2 public GGUF — no auth required
        downloadUrl = "https://huggingface.co/salivosa/moondream2-gguf/resolve/main/moondream2-q4_k.gguf",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 735_000_000L
    ),
    QWEN3_1_7B_Q4(
        displayName = "Memory AI Model",
        fileName = "qwen2_5_1_5b_q4.gguf",
        // Qwen2.5-1.5B-Instruct Q4_K_M — confirmed public on HuggingFace, no token required
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sha256 = "REPLACE_WITH_ACTUAL_SHA256",
        sizeBytes = 1_117_320_736L
    );

    val subDir: String get() = when (this) {
        WHISPER_HINDI_MEDIUM_Q4, WHISPER_LARGE_V3_TURBO_Q4, WHISPER_SMALL_Q4 -> "asr"
        QWEN3_VL_4B_QAIRT, MOONDREAM2_Q4 -> "vlm"
        QWEN3_1_7B_Q4 -> "llm"
    }
}
