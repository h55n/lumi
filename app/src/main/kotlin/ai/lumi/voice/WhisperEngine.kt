package ai.lumi.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the two-stage ASR pipeline:
 * 1. Language detection (Whisper Turbo, 3s clip)
 * 2. Full transcription via language-specific model
 *
 * Flow:
 *   audio → [TurboWhisperEngine.detectLanguage] → route to:
 *     "hi"  → [HindiWhisperEngine.transcribe]
 *     "en"  → [TurboWhisperEngine.transcribe]
 *     else  → [TurboWhisperEngine.transcribe] (best-effort)
 */
import ai.lumi.cloud.SecureKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
class WhisperEngine @Inject constructor(
    private val languageDetector: LanguageDetector,
    private val hindiWhisperEngine: HindiWhisperEngine,
    private val turboWhisperEngine: TurboWhisperEngine
) {

    /**
     * Detect language then transcribe [audioBuffer] (full recording).
     * Priority:
     *  1. Local model (Hindi fine-tuned or Whisper Turbo)
     *  2. Groq Whisper Cloud (whisper-large-v3, ~150ms latency)
     * Returns [TranscriptResult] with text + language code.
     */
    suspend fun detectAndRoute(audioBuffer: FloatArray): TranscriptResult =
        withContext(Dispatchers.IO) {
            val detected = languageDetector.detect(audioBuffer)
            Timber.d("Language detected: ${detected.code} (conf=${detected.confidence})")

            var result = when {
                // Low-confidence language identification must not force a
                // language-specific model on Hinglish or mixed-language speech.
                detected.confidence < 0.75f -> turboWhisperEngine.transcribe(audioBuffer)
                detected.code == "hi" -> {
                    Timber.d("Routing to Hindi fine-tuned model")
                    hindiWhisperEngine.transcribe(audioBuffer)
                }
                else -> {
                    Timber.d("Routing to Whisper Turbo")
                    turboWhisperEngine.transcribe(audioBuffer)
                }
            }

            // Do not retain a Hindi-only transcription for an English-dominant
            // Hinglish utterance. At least 40% of tokens must provide Hindi evidence.
            if (detected.code == "hi" && detected.confidence >= 0.75f &&
                hindiEvidenceRatio(result.text) < 0.40f) {
                val turboResult = turboWhisperEngine.transcribe(audioBuffer)
                if (turboResult.text.isNotBlank()) result = turboResult
            }

            // If local model produced no text (not downloaded / mock), fall back to Groq Cloud Whisper
            if (result.text.isBlank()) {
                Timber.i("Local Whisper produced no text — trying Groq Cloud Whisper (whisper-large-v3)")
                val cloudResult = transcribeWithGroq(audioBuffer, detected.code)
                if (cloudResult != null && cloudResult.text.isNotBlank()) {
                    result = cloudResult
                }
            }

            // Turbo can transcribe Hinglish in Latin characters and label it
            // English. Preserve the detected/preferred Hindi conversation
            // language so downstream guidance replies in Hindi.
            if (detected.code == "hi" && result.text.isNotBlank()) {
                result = result.copy(languageCode = "hi")
            }

            Timber.i("ASR final result: '${result.text}' [${result.languageCode}]")
            result
        }

    private fun hindiEvidenceRatio(text: String): Float {
        val tokens = text.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return 0f
        val hindiWords = setOf("hai", "hain", "karo", "kar", "mujhe", "mera", "meri", "ko", "ki", "ka", "se", "aur", "nahi", "haan", "kripya")
        val evidence = tokens.count { token ->
            token.any { it in '\u0900'..'\u097f' } || token in hindiWords
        }
        return evidence.toFloat() / tokens.size
    }

    private fun transcribeWithGroq(audioBuffer: FloatArray, expectedLang: String): TranscriptResult? {
        val apiKey = SecureKeyStore.getGroqKey() ?: return null
        if (audioBuffer.isEmpty()) return null

        try {
            // Convert FloatArray (16kHz mono [-1f, 1f]) to 16-bit PCM bytes
            val pcmData = ByteArray(audioBuffer.size * 2)
            for (i in audioBuffer.indices) {
                val s = (audioBuffer[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                pcmData[i * 2] = (s.toInt() and 0xFF).toByte()
                pcmData[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            }

            // Build 44-byte WAV header
            val totalDataLen = pcmData.size + 36
            val totalAudioLen = pcmData.size
            val sampleRate = 16000
            val byteRate = 16000 * 2
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0 // PCM format
            header[22] = 1; header[23] = 0 // Mono
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2; header[33] = 0 // Block align
            header[34] = 16; header[35] = 0 // Bits per sample
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            val wavBytes = ByteArray(header.size + pcmData.size)
            System.arraycopy(header, 0, wavBytes, 0, header.size)
            System.arraycopy(pcmData, 0, wavBytes, header.size, pcmData.size)

            val mediaType = "audio/wav".toMediaType()
            val fileBody = wavBytes.toRequestBody(mediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("language", expectedLang)
                .addFormDataPart("file", "audio.wav", fileBody)
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .readTimeout(java.time.Duration.ofSeconds(10))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val text = json.optString("text", "").trim()
                val rawLang = json.optString("language", "en").lowercase()
                val lang = when {
                    rawLang.contains("hindi") || rawLang == "hi" -> "hi"
                    rawLang.contains("english") || rawLang == "en" -> "en"
                    else -> rawLang
                }
                if (text.isNotBlank()) {
                    Timber.i("Groq Whisper STT succeeded: '$text' [$lang]")
                    return TranscriptResult(text, lang, 0.98f)
                }
            } else {
                Timber.w("Groq Whisper HTTP error: ${response.code} — ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error calling Groq Whisper STT")
        }
        return null
    }

    /** Warm up all ASR models (call at OverlayService start). */
    suspend fun warmUp() {
        hindiWhisperEngine.warmUp()
        turboWhisperEngine.warmUp()
    }

    fun release() {
        hindiWhisperEngine.unload()
        turboWhisperEngine.release()
    }
}
