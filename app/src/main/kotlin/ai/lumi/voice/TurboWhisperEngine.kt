package ai.lumi.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.lumi.inference.ModelDownloadManager
import ai.lumi.inference.ModelSpec
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Whisper ASR. Prefers the Large v3 Turbo Q5_0 artifact and falls back
 * to Whisper Small Q5_1 when that is the verified artifact selected for a budget device.
 *
 * Responsibilities:
 *  1. Language detection from first 3s of audio (via [detectLanguage])
 *  2. Full English transcription (when language == "en")
 *
 * Runtime: whisper.cpp JNI for the selected local model. Budget devices use
 * Whisper Small through the model selector instead of loading this large model.
 */
@Singleton
class TurboWhisperEngine @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager
) {

    private var ctxPtr: Long = 0L
    private val isNativeAvailable get() = WhisperJNI.loadLibrary()

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (ctxPtr != 0L) return@withContext
        if (!isNativeAvailable) {
            Timber.w("whisper.cpp JNI unavailable — local Turbo ASR disabled")
            return@withContext
        }
        val model = listOf(
            ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0,
            ModelSpec.WHISPER_SMALL_Q5_1
        ).firstOrNull { modelDownloadManager.isDownloaded(it) }
        if (model == null) {
            Timber.w("No supported Whisper model is installed with valid integrity metadata")
            return@withContext
        }
        ctxPtr = WhisperJNI.initContext(modelDownloadManager.modelFile(model).absolutePath)
        if (ctxPtr == 0L) {
            Timber.e("Whisper runtime failed to initialize the verified ${model.displayName} model")
            return@withContext
        }
        Timber.i("${model.displayName} loaded, ctxPtr=$ctxPtr")
    }

    suspend fun detectLanguage(samples: FloatArray): DetectedLanguage = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            return@withContext DetectedLanguage("und", 0.0f)
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
            DetectedLanguage("und", 0f)
        }
    }

    suspend fun transcribe(samples: FloatArray): TranscriptResult = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            return@withContext WhisperJNI.unavailableTranscript("en")
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
