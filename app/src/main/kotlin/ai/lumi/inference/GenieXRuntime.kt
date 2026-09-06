package ai.lumi.inference

import android.graphics.Bitmap
import java.io.File

/**
 * Abstraction over the Qualcomm GenieX SDK.
 *
 * TWO RUNTIMES are needed:
 *  1. [QairtRuntime]  — pre-compiled AI Hub bundles, NPU-only (Qwen3-VL-4B)
 *  2. [LlamaCppRuntime] — GGUF models via GGML Hexagon backend (Moondream2, Whisper Turbo, Qwen3-1.7B)
 *
 * INTEGRATION STEPS (complete before hackathon):
 * ──────────────────────────────────────────────
 * 1. Download GenieX Android SDK from Qualcomm developer portal (requires NDA sign)
 * 2. Place genieX.aar in app/libs/
 * 3. Uncomment the AAR dependency in app/build.gradle.kts
 * 4. Replace [MockQairtRuntime] with the real Qualcomm implementation
 * 5. Download Qwen3-VL-4B bundle from AI Hub (requires account + device auth)
 * 6. Run the AI Hub compile step targeting SM8850-AC
 *
 * FALLBACK: if GenieX is unavailable, [LlamaCppVulkanRuntime] uses llama.cpp with Vulkan backend.
 */
interface GenieXVLMSession {
    /**
     * Run vision-language inference on [bitmap] with [prompt].
     * Returns raw JSON string from the model.
     */
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
 * Mock implementation used when GenieX SDK is not available (dev machines, CI).
 * Returns deterministic canned responses for known screens.
 */
class MockQairtRuntime : GenieXVLMSession {
    override suspend fun infer(bitmap: Bitmap, prompt: String, systemPrompt: String): String {
        // Simulate ~2s on-device NPU inference
        kotlinx.coroutines.delay(2000)
        val isHindi = systemPrompt.contains("Hindi", ignoreCase = true) || prompt.contains("Hindi", ignoreCase = true)
        val instruction = if (isHindi) "Yahan tap karo — yeh Pay button hai" else "Tap the Pay button here"
        return """{"target": "Pay button", "instruction": "$instruction", "x": 0.5, "y": 0.85}"""
    }

    override fun close() { /* no-op */ }
}

class MockTextLLMRuntime : GenieXTextSession {
    override suspend fun generate(prompt: String, maxTokens: Int): String {
        kotlinx.coroutines.delay(1000)
        return """{"facts": [{"key": "name", "value": "User", "confidence": 0.9}]}"""
    }

    override fun close() { /* no-op */ }
}

/**
 * Factory that creates the appropriate runtime based on device tier and SDK availability.
 *
 * Replace [createVLMSession] body with real GenieX SDK calls once the SDK is integrated.
 */
object GenieXRuntimeFactory {

    fun createVLMSession(tier: DeviceTier, modelFile: File): GenieXVLMSession {
        return if (isGenieXAvailable() && tier.supportsGenieXQairt) {
            // TODO: Replace with real GenieX qairt session
            // QualcommGenieXQairtSession(modelFile)
            MockQairtRuntime()
        } else if (isGenieXAvailable() && tier.supportsGenieXLlamaCpp) {
            // TODO: Replace with real GenieX llama_cpp session
            // QualcommGenieXLlamaCppSession(modelFile)
            MockQairtRuntime()
        } else {
            MockQairtRuntime()
        }
    }

    fun createTextSession(tier: DeviceTier, modelFile: File): GenieXTextSession {
        return if (isGenieXAvailable() && tier.supportsMemoryExtraction) {
            // TODO: Replace with real GenieX text session
            MockTextLLMRuntime()
        } else {
            MockTextLLMRuntime()
        }
    }

    private fun isGenieXAvailable(): Boolean {
        return try {
            // Check if GenieX native library is present
            System.loadLibrary("genieX_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}
