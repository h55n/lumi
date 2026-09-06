package ai.lumi.inference

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Moondream2 Q4 GGUF.
 *
 * Used in two scenarios:
 *  1. Fast-path verification of cached UI maps (any tier)
 *  2. Primary VLM on MID_HIGH and BUDGET tiers
 *
 * NOTE: Moondream2 outputs English only.
 *       Multilingual instructions come from pre-built map templates,
 *       not from runtime Moondream2 generation.
 *
 * INTEGRATION:
 *  - FLAGSHIP / MID_HIGH: GenieX llama_cpp (Hexagon NPU via GGML backend)
 *  - BUDGET: llama.cpp with Vulkan (Adreno GPU)
 *  - Replace [MockQairtRuntime] with real session once native libs are compiled.
 */
@Singleton
class MoondreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var session: GenieXVLMSession? = null

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.MOONDREAM2_Q4)

    suspend fun warmUp(tier: DeviceTier) = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        if (!modelFile.exists()) {
            Timber.w("Moondream2 not downloaded — skipping warmup")
            return@withContext
        }
        Timber.d("Warming up Moondream2 (tier=$tier)...")
        session = GenieXRuntimeFactory.createVLMSession(tier, modelFile)
        Timber.i("Moondream2 warm and ready")
    }

    /**
     * Query the model: "What is at coordinates (x%, y%)?" for fast-path cache verification.
     */
    suspend fun verifyElement(screenshot: Bitmap, elementDescription: String): Boolean =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext false
            val prompt = "Is there a '$elementDescription' visible in this screenshot? Answer only YES or NO."
            val result = try {
                s.infer(screenshot, prompt, "Answer only YES or NO.")
            } catch (e: Exception) {
                Timber.e(e, "Moondream2 verifyElement error")
                return@withContext false
            }
            result.contains("YES", ignoreCase = true)
        }

    /**
     * Full analysis for MID_HIGH / BUDGET tiers.
     * Output is English; instruction translation handled by caller via template.
     */
    suspend fun analyse(
        screenshot: Bitmap,
        taskGoal: String,
        language: String
    ): InferenceResult = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext InferenceResult.error("Moondream2 not loaded", language)

        val prompt = "Task: $taskGoal. What single UI element should the user tap next? Reply ONLY as JSON: {\"target\": \"<element>\", \"x\": 0.0, \"y\": 0.0}"
        val systemPrompt = "You are a phone assistant. Answer in JSON only."

        try {
            val raw = s.infer(screenshot, prompt, systemPrompt)
            parseMoondreamOutput(raw, language)
        } catch (e: Exception) {
            Timber.e(e, "Moondream2 analyse error")
            InferenceResult.error(e.message ?: "Inference failed", language)
        }
    }

    private fun parseMoondreamOutput(raw: String, language: String): InferenceResult {
        return try {
            val targetRegex = """"target"\s*:\s*"([^"]+)"""".toRegex()
            val xRegex = """"x"\s*:\s*([\d.]+)""".toRegex()
            val yRegex = """"y"\s*:\s*([\d.]+)""".toRegex()

            val target = targetRegex.find(raw)?.groupValues?.get(1) ?: "button"
            val x = xRegex.find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
            val y = yRegex.find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f

            // Translate instruction to target language from template
            val instruction = translateInstruction(target, language)

            InferenceResult(
                targetDescription = target,
                instruction = instruction,
                language = language,
                relativeX = x,
                relativeY = y,
                confidence = 0.75f
            )
        } catch (e: Exception) {
            InferenceResult.error("Parse error", language)
        }
    }

    private fun translateInstruction(target: String, language: String): String {
        return when (language) {
            "hi" -> "Yahan tap karo — $target"
            "mr" -> "Itha tap kara — $target"
            else -> "Tap $target"
        }
    }

    /** Unload from memory (BUDGET tier sequential loading). */
    fun unload() {
        session?.close()
        session = null
        Timber.d("Moondream2 unloaded")
    }
}
