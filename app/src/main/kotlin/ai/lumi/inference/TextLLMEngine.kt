package ai.lumi.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qwen3-1.7B Q4 for post-task memory extraction.
 * FLAGSHIP tier only — not loaded on MID_HIGH/BUDGET/MINIMAL.
 */
@Singleton
class TextLLMEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var session: GenieXTextSession? = null

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.QWEN3_1_7B_Q4)

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        if (!modelFile.exists()) {
            Timber.w("Qwen3-1.7B not downloaded — memory extraction disabled")
            return@withContext
        }
        Timber.d("Warming up Qwen3-1.7B...")
        session = GenieXRuntimeFactory.createTextSession(DeviceTier.FLAGSHIP, modelFile) ?: run {
            Timber.w("GenieX text runtime is unavailable; memory extraction is disabled")
            return@withContext
        }
        Timber.i("Qwen3-1.7B warm and ready")
    }

    /**
     * Extracts structured facts from [taskTranscript].
     * Returns list of (key, value, confidence) triples.
     */
    suspend fun extractMemories(taskTranscript: String): List<MemoryFact> =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext emptyList()

            val prompt = """
From this conversation, extract factual information about the user.
Return ONLY valid JSON with no extra text:
{"facts": [{"key": "...", "value": "...", "confidence": 0.0–1.0}]}

Conversation:
$taskTranscript
""".trimIndent()

            try {
                val raw = s.generate(prompt, maxTokens = 256)
                parseMemoryFacts(raw)
            } catch (e: Exception) {
                Timber.e(e, "Memory extraction failed")
                emptyList()
            }
        }

    private fun parseMemoryFacts(raw: String): List<MemoryFact> {
        return try {
            val factsRegex = """"key"\s*:\s*"([^"]+)"\s*,\s*"value"\s*:\s*"([^"]+)"\s*,\s*"confidence"\s*:\s*([\d.]+)""".toRegex()
            factsRegex.findAll(raw).map { match ->
                MemoryFact(
                    key = match.groupValues[1],
                    value = match.groupValues[2],
                    confidence = match.groupValues[3].toFloatOrNull() ?: 0.5f
                )
            }.toList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse memory facts from: $raw")
            emptyList()
        }
    }

    fun release() {
        session?.close()
        session = null
    }
}

data class MemoryFact(
    val key: String,
    val value: String,
    val confidence: Float
)
