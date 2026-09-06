package ai.lumi.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android System TTS wrapper.
 * Supports all Indian languages via installed TTS packs (no download required).
 *
 * LATENCY: < 100ms to first audio (always available).
 * QUALITY: Acceptable for short instructional phrases (≤10 words).
 *
 * LANGUAGE PACK INSTALLATION NOTE:
 * Hindi (hi-IN) is pre-installed on most Indian Android devices.
 * Marathi, Tamil, Bengali: may need installation from Settings → Language & Input → TTS.
 * The onboarding flow should check and prompt for installation if missing.
 */
@Singleton
class SystemTTSWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val initDeferred = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            Timber.i("SystemTTS initialized")
            initDeferred.complete(true)
        } else {
            Timber.e("SystemTTS init failed with status=$status")
            initDeferred.complete(false)
        }
    }

    /** Wait for TTS engine to be ready. */
    suspend fun awaitReady(): Boolean = initDeferred.await()

    /**
     * Speak [text] in [language] (BCP-47 code).
     * Returns when utterance is complete.
     */
    suspend fun speak(text: String, language: String) = withContext(Dispatchers.Main) {
        val engine = tts ?: run {
            Timber.e("SystemTTS not initialized")
            return@withContext
        }
        if (!isReady) awaitReady()

        val locale = bcp47ToLocale(language)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.w("TTS language not supported: $language — falling back to English")
            engine.setLanguage(Locale.ENGLISH)
        } else {
            // Try to find a better quality voice for this locale
            try {
                val voices = engine.voices
                if (voices != null) {
                    val betterVoice = voices.firstOrNull { 
                        it.locale.language == locale.language && 
                        (it.name.contains("local") || it.name.contains("network")) 
                    }
                    if (betterVoice != null) {
                        engine.voice = betterVoice
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not set specific voice")
            }
        }

        // Slower speaking rate for elderly users
        engine.setSpeechRate(0.85f)
        engine.setPitch(1.0f)

        val done = CompletableDeferred<Unit>()
        val utteranceId = "lumi_${System.currentTimeMillis()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { done.complete(Unit) }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        done.await()
    }

    /** Stop any current speech immediately. */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private fun bcp47ToLocale(code: String): Locale {
        val normalized = code.replace('_', '-')
        val parsed = Locale.forLanguageTag(normalized)
        if (parsed.language.isNotBlank()) return parsed
        return when (code.substringBefore('-').lowercase()) {
            "hi" -> Locale("hi", "IN")
            "mr" -> Locale("mr", "IN")
            "ta" -> Locale("ta", "IN")
            "bn" -> Locale("bn", "IN")
            "te" -> Locale("te", "IN")
            "gu" -> Locale("gu", "IN")
            "pa" -> Locale("pa", "IN")
            "kn" -> Locale("kn", "IN")
            "ml" -> Locale("ml", "IN")
            else -> Locale.ENGLISH
        }
    }
}
