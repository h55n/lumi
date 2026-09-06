package ai.lumi.inference

/**
 * Device capability tier, auto-detected at first launch by [ModelSelector].
 * Determines which models and runtimes are loaded.
 */
enum class DeviceTier {
    /**
     * iQOO 15 / Snapdragon 8 Elite Gen 5 (SM8850) + ≥6 GB free RAM.
     * Loads: Qwen3-VL-4B (GenieX qairt, NPU) + Whisper Turbo (GenieX llama_cpp) +
     *         vasista22 Hindi Whisper + Kokoro TTS + Qwen3-1.7B (memory extraction)
     */
    FLAGSHIP,

    /**
     * SD 8 Elite Gen 4 / SD 8 Gen 3 + ≥4 GB free RAM.
     * Loads: Moondream2 Q4 (GenieX llama_cpp) + Whisper Turbo (Vulkan) + Hindi Whisper + Kokoro
     */
    MID_HIGH,

    /**
     * SD 7-series / older 8-series / ≥2 GB free RAM.
     * Loads: Moondream2 Q4 (llama.cpp + Vulkan) + Whisper Small + Hindi Whisper + Kokoro
     * Sequential model loading with unload between steps.
     */
    BUDGET,

    /**
     * Fallback for very low-memory devices.
     * No on-device VLM inference. Pre-built UI maps + System TTS only.
     * Voice activation becomes tap-only.
     */
    MINIMAL;

    val supportsVLM: Boolean get() = this != MINIMAL
    val supportsFlagshipVLM: Boolean get() = this == FLAGSHIP
    val supportsMemoryExtraction: Boolean get() = this == FLAGSHIP
    val supportsGenieXQairt: Boolean get() = this == FLAGSHIP
    val supportsGenieXLlamaCpp: Boolean get() = this == FLAGSHIP || this == MID_HIGH
}
