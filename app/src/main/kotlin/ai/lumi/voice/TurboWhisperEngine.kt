package ai.lumi.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.lumi.inference.ModelDownloadManager
import ai.lumi.inference.ModelSpec
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whisper Large v3 Turbo Q4 GGUF.
 *
 * Responsibilities:
 *  1. Language detection from first 3s of audio (via [detectLanguage])
 *  2. Full English transcription (when language == "en")
 *
 * Runtime:
 *  - FLAGSHIP: GenieX llama_cpp (Hexagon NPU via GGML backend)
 *  - MID_HIGH: whisper.cpp with Vulkan (Adreno GPU)
 *  - BUDGET: Replaced by WhisperSmall; this class still exists but is not instantiated
 */
@Singleton
class TurboWhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var ctxPtr: Long = 0L
    private val isNativeAvailable get() = WhisperJNI.loadLibrary()

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (ctxPtr != 0L) return@withContext
        if (!isNativeAvailable) {
            Timber.w("whisper.cpp JNI not available — using mock")
            return@withContext
        }
        if (!modelFile.exists()) {
            Timber.w("Whisper Turbo model not downloaded")
            return@withContext
        }
        ctxPtr = WhisperJNI.initContext(modelFile.absolutePath)
        Timber.i("Whisper Turbo loaded, ctxPtr=$ctxPtr")
    }

    suspend fun detectLanguage(samples: FloatArray): DetectedLanguage = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            return@withContext DetectedLanguage("en", 0.5f) // mock fallback
        }
        try {
            val json = WhisperJNI.detectLanguage(ctxPtr, samples)
            val obj = JSONObject(json)
            DetectedLanguage(
                code = obj.getString("language"),
                confidence = obj.getDouble("confidence").toFloat()
            )
        } catch (e: Exception) {
            Timber.e(e, "detectLanguage error")
            DetectedLanguage("en", 0f)
        }
    }

    suspend fun transcribe(samples: FloatArray): TranscriptResult = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            return@withContext WhisperJNI.transcribeMock(samples, "en")
        }
        try {
            val json = WhisperJNI.transcribeWithParams(
                ctxPtr = ctxPtr,
                samples = samples,
                numThreads = 4,
                translate = false,
                noTimestamps = true,
                singleSegment = false,
                printProgress = false
            )
            parseTranscriptJson(json, "en")
        } catch (e: Exception) {
            Timber.e(e, "Turbo transcribe error")
            TranscriptResult("", "en", 0f)
        }
    }

    private fun parseTranscriptJson(json: String, language: String): TranscriptResult {
        val obj = JSONObject(json)
        return TranscriptResult(
            text = obj.optString("text", "").trim(),
            languageCode = obj.optString("language", language),
            confidence = obj.optDouble("confidence", 0.9).toFloat()
        )
    }

    fun release() {
        if (ctxPtr != 0L) {
            WhisperJNI.freeContext(ctxPtr)
            ctxPtr = 0L
        }
    }
}
