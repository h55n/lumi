package ai.lumi.voice

import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import ai.lumi.cloud.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.media.MediaPlayer
import java.io.File

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Routes TTS requests to the appropriate engine based on user preference.
 */
@Singleton
class TTSEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groqTTS: GroqTTSClient,
    private val systemTTS: SystemTTSWrapper,
    private val elevenLabsTTS: ElevenLabsTTSClient
) {

    @Volatile private var activeMediaPlayer: android.media.MediaPlayer? = null

    /** Speak [text] in [language] (BCP-47). Suspends until playback completes. */
    suspend fun speak(text: String, language: String) {
        // Detect actual script of the text to prevent garbled output.
        // If requested language is Hindi but text is all Latin/ASCII (model replied in English),
        // speak in English so it's intelligible. If text has Devanagari, speak in Hindi.
        val effectiveLang = resolveEffectiveLanguage(text, language)
        Timber.d("TTSEngine.speak: '$text' [requested=$language, effective=$effectiveLang]")

        val provider = SecureKeyStore.getActiveTtsProvider()

        when (provider) {
            "elevenlabs" -> {
                val key = SecureKeyStore.getElevenLabsKey()
                if (!key.isNullOrBlank()) {
                    val mp3 = elevenLabsTTS.synthesize(text, key)
                    if (mp3 != null && mp3.isNotEmpty()) {
                        playMp3(mp3)
                        return
                    }
                }
                Timber.w("ElevenLabs failed — falling back to default route")
                routeDefault(text, effectiveLang)
                return
            }
            "sarvam", "system" -> {
                fallbackSystem(text, effectiveLang)
                return
            }
        }

        // Default route
        routeDefault(text, effectiveLang)
    }

    /**
     * Resolves the effective TTS language based on the actual script of [text].
     * If the requested [language] is an Indic language but the text contains
     * no Indic characters (model replied in English/Romanized), fall back to English
     * so System TTS can pronounce it correctly.
     */
    private fun resolveEffectiveLanguage(text: String, language: String): String =
        TtsLanguageResolver.resolve(text, language).also { effective ->
            if (effective != language.substringBefore('-').lowercase()) {
                Timber.w("resolveEffectiveLanguage: overriding $language → $effective for response script")
            }
        }

    private suspend fun routeDefault(text: String, language: String) {
        val isHindi = language.lowercase().startsWith("hi")
        if (!isHindi) {
            if (tryGroq(text, language)) return
        }
        fallbackSystem(text, language)
    }

    private suspend fun tryGroq(text: String, language: String): Boolean {
        if (!isNetworkAvailable(context)) {
            Timber.w("Groq TTS skipped: no network")
            return false
        }
        val key = SecureKeyStore.getGroqKey()
        if (key.isNullOrBlank()) {
            Timber.w("Groq TTS skipped: no API key")
            return false
        }
        val wav = groqTTS.synthesize(text, key)
        if (wav != null && wav.isNotEmpty()) {
            playWav(wav)
            return true
        }
        return false
    }


    
    private suspend fun fallbackSystem(text: String, language: String) {
        Timber.w("Falling back to System TTS for $language")
        systemTTS.speak(text, language)
    }
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null && 
               cm.getNetworkCapabilities(cm.activeNetwork)
                 ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Stop active playback immediately. */
    fun stop() {
        systemTTS.stop()
        try { activeMediaPlayer?.stop(); activeMediaPlayer?.release() } catch (_: Exception) {}
        activeMediaPlayer = null
    }

    suspend fun warmUp() {
        runCatching { systemTTS.awaitReady() }.onFailure {
            Timber.e(it, "TTSEngine.warmUp: SystemTTS init error (non-fatal)")
        }
    }

    fun release() {
        systemTTS.shutdown()
    }

    private suspend fun playMp3(audioBytes: ByteArray) = withContext(Dispatchers.IO) {
        playTempAudio(audioBytes, ".mp3")
    }

    private suspend fun playWav(audioBytes: ByteArray) = withContext(Dispatchers.IO) {
        playTempAudio(audioBytes, ".wav")
    }

    private suspend fun playTempAudio(audioBytes: ByteArray, suffix: String) {
        try {
            val tempFile = File.createTempFile("tts_", suffix, context.cacheDir)
            tempFile.writeBytes(audioBytes)
            val player = android.media.MediaPlayer()
            activeMediaPlayer = player
            player.setDataSource(tempFile.absolutePath)
            player.prepare()
            player.start()
            
            val latch = java.util.concurrent.CountDownLatch(1)
            player.setOnCompletionListener { 
                activeMediaPlayer = null
                player.release()
                tempFile.delete()
                latch.countDown()
            }
            player.setOnErrorListener { _, _, _ ->
                activeMediaPlayer = null
                player.release()
                tempFile.delete()
                latch.countDown()
                true
            }
            
            // Wait with 60s max timeout to prevent hanging
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio $suffix")
        }
    }
}
