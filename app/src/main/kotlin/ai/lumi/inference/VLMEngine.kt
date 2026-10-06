package ai.lumi.inference

import android.graphics.Bitmap
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Qwen3-VL-4B via GenieX qairt runtime (NPU-compiled).
 * Used exclusively on [DeviceTier.FLAGSHIP].
 *
 * PROMPT CONTRACT:
 * The model is prompted to return a JSON object:
 *   {"target": "<element>", "instruction": "<≤10 words>", "x": 0.0–1.0, "y": 0.0–1.0}
 *
 * If the model defaults to English/Chinese despite the system prompt:
 *  → Increase temperature to 0.1–0.3
 *  → Add a few-shot Hindi example in the system prompt
 *  → Flag: test Hindi adherence on Day 1 of build
 */
@Singleton
class VLMEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var session: GenieXVLMSession? = null
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.QWEN3_VL_4B_QAIRT)

    /**
     * Pre-warm the model into memory (call at OverlayService start on FLAGSHIP).
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        if (!modelFile.exists()) {
            Timber.w("Qwen3-VL-4B not downloaded — skipping warmup")
            return@withContext
        }
        Timber.d("Warming up Qwen3-VL-4B...")
        session = GenieXRuntimeFactory.createVLMSession(DeviceTier.FLAGSHIP, modelFile) ?: run {
            Timber.w("GenieX runtime is unavailable; local VLM guidance is disabled")
            return@withContext
        }
        Timber.i("Qwen3-VL-4B warm and ready")
    }

    /**
     * Analyse [screenshot] and return the next guidance step in [language].
     *
     * @param screenshot    1280×720 JPEG bitmap (downscaled by ScreenshotProcessor)
     * @param taskGoal      User's spoken goal, transcribed ("PhonePe par 500 rupaye bhejo")
     * @param language      Detected language BCP-47 ("hi", "en")
     * @param stepHistory   Previous steps taken in this task
     */
    suspend fun analyse(
        screenshot: Bitmap,
        taskGoal: String,
        language: String,
        stepHistory: List<String> = emptyList()
    ): InferenceResult = withContext(Dispatchers.IO) {
        val s = session ?: run {
            warmUp()
            session ?: return@withContext InferenceResult.error("Model not loaded", language)
        }

        val systemPrompt = buildSystemPrompt(language)
        val userPrompt = buildUserPrompt(taskGoal, stepHistory, language)

        Timber.d("VLMEngine.analyse: request [language=$language]")

        try {
            val rawJson = s.infer(screenshot, userPrompt, systemPrompt)
            parseModelOutput(rawJson, language)
        } catch (e: Exception) {
            Timber.e(e, "VLMEngine inference error")
            InferenceResult.error(e.message ?: "Inference failed", language)
        }
    }

    private fun buildSystemPrompt(language: String): String {
        val langName = when (language) {
            "hi" -> "Hindi"
            "mr" -> "Marathi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "bn" -> "Bengali"
            "gu" -> "Gujarati"
            "pa" -> "Punjabi"
            "ml" -> "Malayalam"
            "kn" -> "Kannada"
            else -> "English"
        }
        val fewShotExample = if (language != "en") """
Example response in $langName:
{"target": "Pay button", "instruction": "Yahan tap karein", "x": 0.5, "y": 0.85}
""" else ""

        return """
You are Lumi, a phone guidance assistant for first-time smartphone users.
The user speaks $langName.
You MUST respond ONLY in $langName. English is NOT acceptable.
Use simple, short words. Maximum 10 words per instruction.
Identify the exact UI element to tap next and give ONE instruction.
$fewShotExample
ALWAYS respond with ONLY this JSON (no other text):
{"target": "<element description>", "instruction": "<short instruction in $langName>", "x": <0.0-1.0>, "y": <0.0-1.0>}

If the task is complete, respond:
{"target": "", "instruction": "<completion message in $langName>", "x": 0, "y": 0, "done": true}
""".trimIndent()
    }

    private fun buildUserPrompt(goal: String, history: List<String>, language: String): String {
        val historyText = if (history.isEmpty()) "" else "\nPrevious steps: ${history.joinToString(", ")}"
        return "Goal: $goal$historyText\nWhat is the next step?"
    }

    private fun parseModelOutput(rawJson: String, language: String): InferenceResult {
        return try {
            val clean = rawJson.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val adapter = moshi.adapter(VLMOutputDto::class.java)
            val dto = adapter.fromJson(clean) ?: throw IllegalStateException("null parse")

            if (dto.done == true) {
                InferenceResult.taskComplete(language)
            } else {
                InferenceResult(
                    targetDescription = dto.target ?: "",
                    instruction = dto.instruction ?: "",
                    language = language,
                    relativeX = dto.x ?: 0f,
                    relativeY = dto.y ?: 0f,
                    confidence = dto.confidence ?: 0.85f
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse VLM output")
            InferenceResult.error("Parse error", language)
        }
    }

    fun release() {
        session?.close()
        session = null
    }

    private data class VLMOutputDto(
        val target: String?,
        val instruction: String?,
        val x: Float?,
        val y: Float?,
        val confidence: Float?,
        val done: Boolean?
    )
}
