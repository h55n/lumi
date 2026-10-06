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
 * Uses the pinned multilingual Whisper Medium Q5_0 artifact for Hindi ASR.
 * This is not a Hindi-specific fine-tune; no benchmark claim is made.
 *
 * Runtime: whisper.cpp with Vulkan backend (Adreno GPU). The configured
 * artifact is multilingual and is not a Hindi-specific fine-tune.
 */
@Singleton
class HindiWhisperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager
) {

    private var ctxPtr: Long = 0L
    private val isNativeAvailable get() = WhisperJNI.loadLibrary()

    private val modelFile: File
        get() = modelDownloadManager.modelFile(ModelSpec.WHISPER_MEDIUM_Q5_0)

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (ctxPtr != 0L) return@withContext
        if (!isNativeAvailable) {
            Timber.w("whisper.cpp JNI unavailable — local Hindi ASR disabled")
            return@withContext
        }
        if (!modelDownloadManager.isDownloaded(ModelSpec.WHISPER_MEDIUM_Q5_0)) {
            Timber.w("Hindi Whisper model is missing or failed integrity verification — will use Turbo fallback")
            return@withContext
        }
        ctxPtr = WhisperJNI.initContext(modelFile.absolutePath)
        Timber.i("Hindi Whisper medium loaded, ctxPtr=$ctxPtr")
    }

    /**
     * Transcribe [samples] (16 kHz, mono, float32 PCM) with the multilingual model.
     * Returns an empty result if local ASR is unavailable; the router may use cloud ASR.
     */
    suspend fun transcribe(samples: FloatArray): TranscriptResult = withContext(Dispatchers.IO) {
        if (!isNativeAvailable || ctxPtr == 0L) {
            Timber.w("Hindi Whisper not ready — returning empty local result")
            return@withContext WhisperJNI.unavailableTranscript("hi")
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
