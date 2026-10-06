package ai.lumi.inference

import android.graphics.Bitmap
import java.io.File

/**
 * Abstraction over the Qualcomm GenieX SDK.
 *
 * Real QAIRT/llama.cpp adapters are not integrated in this repository yet. Until they are,
 * the factories below return null so the app cannot mistake canned output for live inference.
 */
interface GenieXVLMSession {
    /** Run vision-language inference on [bitmap] with [prompt]. */
    suspend fun infer(bitmap: Bitmap, prompt: String, systemPrompt: String): String

    /** Release native resources. */
    fun close()
}

interface GenieXTextSession {
    /** Run text-only inference (for memory extraction). */
    suspend fun generate(prompt: String, maxTokens: Int = 256): String
    fun close()
}

/**
 * Runtime factory. Return null until a real native session can be created and validated.
 * A deterministic fake response is unsafe here because downstream code treats it as model output.
 */
object GenieXRuntimeFactory {
    fun createVLMSession(tier: DeviceTier, modelFile: File): GenieXVLMSession? {
        if (!modelFile.isFile) return null
        if (!tier.supportsGenieXQairt && !tier.supportsGenieXLlamaCpp) return null

        // TODO: create and validate the real Qualcomm session before enabling local VLM guidance.
        return null
    }

    fun createTextSession(tier: DeviceTier, modelFile: File): GenieXTextSession? {
        if (!modelFile.isFile || !tier.supportsMemoryExtraction) return null

        // TODO: create and validate the real Qualcomm text session before enabling extraction.
        return null
    }
}
