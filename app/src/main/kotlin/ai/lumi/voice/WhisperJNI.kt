package ai.lumi.voice

/**
 * JNI bridge to whisper.cpp compiled for Android arm64-v8a.
 *
 * INTEGRATION STEPS:
 * ──────────────────
 * 1. Clone whisper.cpp: https://github.com/ggerganov/whisper.cpp
 * 2. Build for Android:
 *      cd whisper.cpp
 *      mkdir build-android && cd build-android
 *      cmake .. -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
 *               -DANDROID_ABI=arm64-v8a \
 *               -DANDROID_PLATFORM=android-29 \
 *               -DWHISPER_BUILD_ANDROID=ON \
 *               -DWHISPER_METAL=OFF \
 *               -DGGML_VULKAN=ON
 *      make -j8
 * 3. Copy libwhisper.so → app/src/main/jniLibs/arm64-v8a/
 * 4. Copy the Java/Kotlin JNI bindings from whisper.cpp/examples/whisper.android/
 *    into this package (replace this file).
 *
 * PERFORMANCE NOTE:
 * Run with Vulkan backend (Adreno GPU) for whisper.cpp since GenieX llama_cpp
 * is preferred for the flagship turbo model.
 */
object WhisperJNI {

    private var isLibraryLoaded = false

    fun loadLibrary(): Boolean {
        if (isLibraryLoaded) return true
        return try {
            try {
                System.loadLibrary("omp")
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
            } catch (t: Throwable) {
                // Pre-loading optional if runtime linker auto-resolves DT_NEEDED
            }
            try {
                // Try ARMv8.2-a FP16 vector accelerated library (fastest on Snapdragon 8 Elite)
                System.loadLibrary("whisper_v8fp16_va")
                timber.log.Timber.i("libwhisper_v8fp16_va loaded successfully")
            } catch (t: Throwable) {
                System.loadLibrary("whisper")
                timber.log.Timber.i("libwhisper loaded successfully")
            }
            isLibraryLoaded = true
            true
        } catch (e: Throwable) {
            timber.log.Timber.e(e, "Failed to load whisper native library")
            isLibraryLoaded = false
            false
        }
    }

    // ── Native methods (implemented in libwhisper.so) ──────────────────────────

    @JvmStatic external fun initContext(modelPath: String): Long

    @JvmStatic external fun initContextFromAsset(assetMgr: android.content.res.AssetManager, assetPath: String): Long

    @JvmStatic external fun freeContext(ctxPtr: Long)

    /**
     * Transcribe 16kHz mono PCM float32 audio.
     * @param ctxPtr  Native context pointer from [initContext]
     * @param samples Float32 PCM samples at 16000 Hz
     * @param numThreads  Number of threads (use 4 on flagship, 2 on budget)
     * @return JSON string: {"text": "...", "language": "hi", "segments": [...]}
     */
    @JvmStatic external fun transcribeWithParams(
        ctxPtr: Long,
        samples: FloatArray,
        numThreads: Int,
        translate: Boolean,
        noTimestamps: Boolean,
        singleSegment: Boolean,
        printProgress: Boolean
    ): String

    /**
     * Detect language from the first 3 seconds of audio.
     * @return JSON: {"language": "hi", "confidence": 0.97}
     */
    @JvmStatic external fun detectLanguage(ctxPtr: Long, samples: FloatArray): String

    // ── Mock implementations for when native lib is not available ──────────────

    fun transcribeMock(samples: FloatArray, language: String): TranscriptResult {
        // Return empty result when native engine is unavailable (cloud or system ASR handles it)
        return TranscriptResult(
            text = "",
            languageCode = language,
            confidence = 0.0f
        )
    }
}

data class TranscriptResult(
    val text: String,
    val languageCode: String,
    val confidence: Float,
    val segments: List<AudioSegment> = emptyList()
)

data class AudioSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)
