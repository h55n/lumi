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
 * vasista22/whisper-hindi-medium-Q4 — fine-tuned Hindi ASR.
 * WER: 8.2% on FLEURS-Hindi benchmark.
 *
 * Runtime: whisper.cpp with Vulkan backend (Adreno GPU — all device tiers).
 * This is NOT run via GenieX; whisper.cpp + Vulkan is used directly for
 * the fine-tuned model since GenieX bundles are not available for it.
 */
@Singleton
class HindiWhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var ctxPtr: Long = 0L
    private val isNativeAvailable get() = WhisperJNI.loadLibrary()

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (ctxPtr != 0L) return@withContext
        if (!isNativeAvailable) {
            Timber.w("whisper.cpp JNI not available — using mock for Hindi")
            return@withContext
        }
        if (!modelFile.exists()) {
            Timber.w("Hindi Whisper model not downloaded — will use Turbo fallback")
            return@withContext
        }
        ctxPtr = WhisperJNI.initContext(modelFile.absolutePath)
        Timber.i("Hindi Whisper loaded, ctxPtr=$ctxPtr (WER 8.2% on FLEURS-Hindi)")
    }

    /**
     * Transcribe [samples] (16 kHz, mono, float32 PCM) in Hindi.
     * Falls back to mock if model is not available.
     */
    suspend fun transcribe(samples: FloatArray): TranscriptResult = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            Timber.w("Hindi Whisper not ready — mock fallback")
            return@withContext WhisperJNI.transcribeMock(samples, "hi")
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
            parseTranscriptJson(json)
        } catch (e: Exception) {
            Timber.e(e, "Hindi transcription error")
            TranscriptResult("", "hi", 0f)
        }
    }

    private fun parseTranscriptJson(json: String): TranscriptResult {
        val obj = JSONObject(json)
        return TranscriptResult(
            text = obj.optString("text", "").trim(),
            languageCode = "hi",
            confidence = obj.optDouble("confidence", 0.9).toFloat()
        )
    }

    /** Unload from RAM — used on BUDGET tier to free memory between steps. */
    fun unload() {
        if (ctxPtr != 0L) {
            WhisperJNI.freeContext(ctxPtr)
            ctxPtr = 0L
            Timber.d("Hindi Whisper unloaded")
        }
    }
}
