package ai.lumi.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device capabilities, assigns a [DeviceTier], and selects compatible models.
 * Model names and explicit download controls are available in Settings.
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
     * Returns the models required for [tier] and [languages]. Only models with direct URLs,
     * trusted pinned SHA-256 metadata, and an integrated local runtime are returned.
     */
    fun requiredModels(tier: DeviceTier, languages: Set<String> = emptySet()): List<ModelSpec> = buildList {
        // Large Turbo handles flagship/mid-high ASR. Budget devices use the compact Whisper Small artifact.
        when (tier) {
            DeviceTier.FLAGSHIP, DeviceTier.MID_HIGH ->
                add(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0)
            DeviceTier.BUDGET ->
                add(ModelSpec.WHISPER_SMALL_Q5_1)
            DeviceTier.MINIMAL -> { /* no ASR */ }
        }

        // Hindi uses the multilingual medium model; no dedicated fine-tuned artifact is configured.
        if (languages.contains("hi")) {
            add(ModelSpec.WHISPER_MEDIUM_Q5_0)
        }
        // GenieX adapters are not integrated yet; do not download models that cannot run locally.
        // Local VLM and memory inference remain disabled until real adapters are integrated.
    }.filter { it.isDirectDownloadable && it.hasTrustedSha256 && it.hasRuntimeIntegration }
}

/**
 * Specification for each model artifact.
 *
 * Direct-download entries use public, immutable artifact URLs and trusted SHA-256 metadata.
 * [hasRuntimeIntegration] is false until this repository can create a real inference session;
 * those artifacts are not selected, downloaded, or loaded as if they were usable.
 */
enum class ModelSpec(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Whether this artifact has a public direct-download URL. */
    val isDirectDownloadable: Boolean = true,
    /** Whether a real local inference adapter is integrated for this artifact. */
    val hasRuntimeIntegration: Boolean = true
) {
    WHISPER_MEDIUM_Q5_0(
        displayName = "Multilingual Whisper Medium (Hindi capable)",
        // Preserve the app-private cache name used by earlier releases.
        fileName = "whisper_hindi_medium_q4.bin",
        // Exact upstream Q5_0 artifact, pinned to the commit containing its verified hash.
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-medium-q5_0.bin",
        sha256 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
        sizeBytes = 539_212_467L
    ),
    WHISPER_LARGE_V3_TURBO_Q5_0(
        displayName = "Whisper Large v3 Turbo",
        // Preserve the app-private cache name used by earlier releases.
        fileName = "whisper_large_v3_turbo_q4.bin",
        // Exact upstream Q5_0 artifact, pinned to the commit containing its verified hash.
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/98aa99a0a9db05ae2342309f5096248665f7cba3/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        sizeBytes = 574_041_195L
    ),
    WHISPER_SMALL_Q5_1(
        displayName = "Whisper Small",
        // Preserve the app-private cache name used by earlier releases.
        fileName = "whisper_small_q4.bin",
        // Exact upstream Q5_1 artifact, pinned to the commit containing its verified hash.
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 190_085_487L
    ),
    QWEN3_VL_4B_QAIRT(
        displayName = "Vision AI Model (Qualcomm SDK)",
        fileName = "qwen3_vl_4b_instruct_qairt.zip",
        // Qualcomm AI Hub SDK model — not directly downloadable through this manager.
        downloadUrl = "https://aihub.qualcomm.com/models/qwen3_vl_4b_instruct",
        sha256 = "",
        sizeBytes = 2_700_000_000L,
        isDirectDownloadable = false,
        hasRuntimeIntegration = false
    ),
    MOONDREAM2_Q4_K(
        displayName = "Moondream2 Screen Reader (Q4_K)",
        fileName = "moondream2_q4.gguf",
        // Exact public Q4_K artifact, pinned to the commit containing its verified hash.
        downloadUrl = "https://huggingface.co/salivosa/moondream2-gguf/resolve/205f2e67003198c7bdc7a93f5674dc3069b24513/moondream2-q4_k.gguf",
        sha256 = "77baaa54e41cbfc24e305ee15f58ff2aca198517f658368f1be072d07b51f99d",
        sizeBytes = 919_494_048L,
        hasRuntimeIntegration = false
    ),
    QWEN3_1_7B_Q4_K_M(
        displayName = "Qwen3 1.7B Memory Model (Q4_K_M)",
        // Preserve the old app-private cache path; old Qwen2.5 bytes will fail the new SHA-256.
        fileName = "qwen2_5_1_5b_q4.gguf",
        // Qwen3 1.7B Q4_K_M artifact, pinned to the exact Hugging Face file revision.
        downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/bd59ef4c1c7af8b7ade0d473f3ab0d48b9f1d338/Qwen3-1.7B-Q4_K_M.gguf",
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        sizeBytes = 1_107_409_472L,
        hasRuntimeIntegration = false
    );

    val hasTrustedSha256: Boolean
        get() = ModelIntegrity.isValidSha256(sha256)

    val subDir: String get() = when (this) {
        WHISPER_MEDIUM_Q5_0, WHISPER_LARGE_V3_TURBO_Q5_0, WHISPER_SMALL_Q5_1 -> "asr"
        QWEN3_VL_4B_QAIRT, MOONDREAM2_Q4_K -> "vlm"
        QWEN3_1_7B_Q4_K_M -> "llm"
    }
}
