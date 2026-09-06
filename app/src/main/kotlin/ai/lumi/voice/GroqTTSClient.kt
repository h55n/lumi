package ai.lumi.voice

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
 * Groq TTS Client using OpenAI-compatible endpoint.
 */
@Singleton
class GroqTTSClient @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(text: String, apiKey: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank() || apiKey.isBlank()) return@withContext null
        try {
            val jsonBody = JSONObject().apply {
                put("model", "canopylabs/orpheus-v1-english")
                put("input", text)
                put("voice", "nova")
                put("response_format", "wav")
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/speech")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Groq TTS error: ${response.code} ${response.body?.string()}")
                    return@withContext null
                }
                
                val audioBytes = response.body?.bytes()
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    Timber.i("Groq TTS ok: ${audioBytes.size} bytes")
                    return@withContext audioBytes
                }
                Timber.e("Groq TTS response missing audio bytes")
                return@withContext null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error calling Groq TTS")
            null
        }
    }
}
