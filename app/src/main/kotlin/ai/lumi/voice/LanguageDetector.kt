package ai.lumi.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

import ai.lumi.data.datastore.LumiPreferences
import kotlinx.coroutines.flow.first

data class DetectedLanguage(
    val code: String,   // BCP-47: "hi", "en", "mr", "ta"
    val confidence: Float
)

@Singleton
class LanguageDetector @Inject constructor(
    private val turboWhisperEngine: TurboWhisperEngine,
    private val preferences: LumiPreferences
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val DETECTION_SECONDS = 3
        private const val DETECTION_SAMPLES = SAMPLE_RATE * DETECTION_SECONDS
        private const val MIN_CONFIDENCE = 0.60f
        private const val DEFAULT_LANGUAGE = "en"
    }

    /**
     * Detect spoken language from [audioBuffer] (full recording).
     * Uses only the first 3 seconds — faster and sufficient for language ID.
     *
     * Falls back to user preferred language if confidence is below [MIN_CONFIDENCE].
     */
    suspend fun detect(audioBuffer: FloatArray): DetectedLanguage = withContext(Dispatchers.IO) {
        val userPref = try { preferences.preferredLanguage.first() } catch (e: Exception) { DEFAULT_LANGUAGE }
        val clip = audioBuffer.take(DETECTION_SAMPLES).toFloatArray()
        val result = try {
            turboWhisperEngine.detectLanguage(clip)
        } catch (e: Exception) {
            Timber.e(e, "Language detection failed — defaulting to user preferred: $userPref")
            DetectedLanguage(userPref, 0f)
        }

        if (result.confidence < MIN_CONFIDENCE) {
            Timber.d("Language detection low confidence (${result.confidence}) — defaulting to: $userPref")
            DetectedLanguage(userPref, result.confidence)
        } else {
            Timber.d("Detected language: ${result.code} (confidence=${result.confidence})")
            result
        }
    }
}
