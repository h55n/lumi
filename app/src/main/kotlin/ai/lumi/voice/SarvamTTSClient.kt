package ai.lumi.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sarvam Bulbul TTS (https://api.sarvam.ai/text-to-speech).
 * Uses bulbul:v3 — current REST schema: `text` + `language_code`.
 */
@Singleton
class SarvamTTSClient @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(text: String, language: String, apiKey: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank() || apiKey.isBlank()) return@withContext null
        try {
            val languageCode = mapLanguageToSarvam(language)
            // Truncate to v3 REST limit (2500 chars) to avoid hard API failures
            val clipped = if (text.length > 2500) text.take(2500) else text

            val jsonBody = JSONObject().apply {
                put("text", clipped)
                put("target_language_code", languageCode)
                put("model", "bulbul:v3")
                put("speaker", "shubh")
                put("pace", 1.0)
                put("speech_sample_rate", 24000)
                put("temperature", 0.6)
                put("output_audio_codec", "wav")
            }

            val request = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.e("Sarvam API error: ${response.code} $responseBody")
                    return@withContext null
                }

                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.has("audios")) {
                    val audios = jsonResponse.getJSONArray("audios")
                    if (audios.length() > 0) {
                        val base64Audio = audios.getString(0)
                        val decoded = Base64.decode(base64Audio, Base64.DEFAULT)
                        if (decoded.isNotEmpty()) {
                            Timber.i("Sarvam TTS ok: ${decoded.size} bytes [$languageCode]")
                            return@withContext decoded
                        }
                    }
                }
                Timber.e("Sarvam API response missing audios array: ${responseBody.take(200)}")
                return@withContext null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error calling Sarvam TTS")
            null
        }
    }

    private fun mapLanguageToSarvam(bcp47: String): String {
        val code = bcp47.lowercase().substringBefore('-')
        return when (code) {
            "hi" -> "hi-IN"
            "mr" -> "mr-IN"
            "ta" -> "ta-IN"
            "te" -> "te-IN"
            "bn" -> "bn-IN"
            "gu" -> "gu-IN"
            "pa" -> "pa-IN"
            "ml" -> "ml-IN"
            "kn" -> "kn-IN"
            "or", "od" -> "od-IN"
            "en" -> "en-IN"
            else -> "hi-IN"
        }
    }
}
