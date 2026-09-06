package ai.lumi.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.lumi.R
import javax.inject.Inject

@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var whisperEngine: WhisperEngine
    @Inject lateinit var ttsEngine: TTSEngine
    @Inject lateinit var preferences: ai.lumi.data.datastore.LumiPreferences

    private var activeLanguage: String = "en"
    private var activeLanguages: Set<String> = setOf("en")
    private var recognizerRetryCount: Int = 0
    private val MAX_RECOGNIZER_RETRIES = 2

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    companion object {
        private const val CHANNEL_ID = "lumi_voice"
        private const val NOTIF_ID = 2002
        const val ACTION_START_LISTENING = "ai.lumi.START_LISTENING"
        const val ACTION_STOP_LISTENING = "ai.lumi.STOP_LISTENING"
        const val ACTION_SPEAK = "ai.lumi.SPEAK"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"

        // AudioRecord constants — 16kHz mono PCM 16-bit converted to float32
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_SEC = 10
        private const val VAD_SILENCE_THRESHOLD = 0.005f
        private const val VAD_SPEECH_THRESHOLD = 0.008f
        private const val VAD_SILENCE_FRAMES = 35 // ~2.2s of silence after speech triggers stop
        private const val MIN_RECORDING_SAMPLES = SAMPLE_RATE * 2 // minimum 2s before silence cutoff

        fun startListening(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to start VoiceService")
                }
            }
        }

        fun stopListening(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }

        fun speak(context: Context, text: String, language: String) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_SPEAK
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_LANGUAGE, language)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to speak via VoiceService")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        serviceScope.launch { runCatching { whisperEngine.warmUp() }.onFailure { Timber.e(it, "whisperEngine warmUp failed (non-fatal)") } }
        serviceScope.launch { runCatching { ttsEngine.warmUp() }.onFailure { Timber.e(it, "ttsEngine warmUp failed (non-fatal)") } }
        serviceScope.launch {
            preferences.preferredLanguages.collect { langs ->
                if (langs.isNotEmpty()) {
                    activeLanguages = langs
                    activeLanguage = langs.first()
                    Timber.d("VoiceService active languages synced to $langs")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startRecording()
            ACTION_STOP_LISTENING -> stopRecording()
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                val lang = intent.getStringExtra(EXTRA_LANGUAGE) ?: activeLanguage
                serviceScope.launch { ttsEngine.speak(text, lang) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        stopRecording()
        recognizerRetryCount = 0

        mainHandler.post {
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(this@VoiceService)) {
                try {
                    startSpeechRecognizer()
                    return@post
                } catch (e: Exception) {
                    Timber.w(e, "SpeechRecognizer initialization failed, falling back to AudioRecord")
                }
            }
            startAudioRecord()
        }
    }

    private fun startSpeechRecognizer() {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).also {
            speechRecognizer = it
        }

        // English is the default conversation language. Hindi remains an
        // additional recognizer language and is selected per utterance below.
        val primaryLangTag = when (activeLanguage) {
            "hi" -> "hi-IN"
            "mr" -> "mr-IN"
            "bn" -> "bn-IN"
            "ta" -> "ta-IN"
            "te" -> "te-IN"
            else -> "en-IN"
        }
        // Build a comma-separated list of all preferred language tags for the recognizer
        // English is the normal command language, while Hindi remains available on
        // every recognition request so a Hindi utterance does not need a settings
        // change before Android can transcribe it correctly.
        val allLangTags = (activeLanguages + setOf("en", "hi")).map { lang ->
            when (lang) {
                "hi" -> "hi-IN"
                "mr" -> "mr-IN"
                "bn" -> "bn-IN"
                "ta" -> "ta-IN"
                "te" -> "te-IN"
                "gu" -> "gu-IN"
                "pa" -> "pa-IN"
                "ml" -> "ml-IN"
                "kn" -> "kn-IN"
                else -> "en-IN"
            }
        }.distinct()

        val recognizerIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, primaryLangTag)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLangTag)
            // Allow recognizer to detect speech even in the non-primary language
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", allLangTags.filter { it != primaryLangTag }.toTypedArray())
            // Increase silence tolerance — Hindi sentences are longer and have natural pauses
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Timber.d("SpeechRecognizer ready for speech [$primaryLangTag], langs=$allLangTags")
            }

            override fun onBeginningOfSpeech() {
                Timber.d("SpeechRecognizer speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Timber.d("SpeechRecognizer speech ended")
            }

            override fun onError(error: Int) {
                Timber.w("SpeechRecognizer error: $error (retry=$recognizerRetryCount)")
                stopSpeechRecognizer()
                when (error) {
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> {
                        // Client-side cancel — do nothing, user probably cancelled
                        Timber.d("SpeechRecognizer: client cancel, ignoring")
                    }
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Retry the recognizer up to MAX_RECOGNIZER_RETRIES before giving up
                        if (recognizerRetryCount < MAX_RECOGNIZER_RETRIES) {
                            recognizerRetryCount++
                            Timber.i("SpeechRecognizer: retrying ($recognizerRetryCount/$MAX_RECOGNIZER_RETRIES)")
                            mainHandler.postDelayed({ startSpeechRecognizer() }, 200L)
                        } else {
                            Timber.w("SpeechRecognizer: max retries hit, falling back to AudioRecord")
                            startAudioRecord()
                        }
                    }
                    android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Give the system time to free the recognizer, then retry
                        mainHandler.postDelayed({
                            recognizerRetryCount++
                            if (recognizerRetryCount <= MAX_RECOGNIZER_RETRIES) startSpeechRecognizer()
                            else startAudioRecord()
                        }, 500L)
                    }
                    else -> {
                        // For all other errors (network, server, etc.) fall back to on-device
                        startAudioRecord()
                    }
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                recognizerRetryCount = 0
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                Timber.i("SpeechRecognizer transcription: '$text' (candidates: ${matches?.take(3)})")
                if (text.isNotBlank()) {
                    val language = if (text.any { it in '\u0900'..'\u097F' }) "hi" else activeLanguage
                    broadcastTranscript(text, language)
                } else {
                    // Empty result — retry once before AudioRecord
                    if (recognizerRetryCount < 1) {
                        recognizerRetryCount++
                        stopSpeechRecognizer()
                        mainHandler.postDelayed({ startSpeechRecognizer() }, 100L)
                        return
                    }
                    startAudioRecord()
                }
                stopSpeechRecognizer()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim() ?: ""
                if (partial.isNotBlank()) {
                    Timber.d("SpeechRecognizer partial: '$partial'")
                    broadcastPartialTranscript(partial)
                }
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        Timber.d("Starting SpeechRecognizer (primary=$primaryLangTag, retry=$recognizerRetryCount)")
        recognizer.startListening(recognizerIntent)
    }

    private fun stopSpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing SpeechRecognizer")
        }
        speechRecognizer = null
    }

    private fun startAudioRecord() {
        if (recordingJob?.isActive == true) return

        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Timber.e("RECORD_AUDIO permission not granted")
            broadcastError("Microphone permission required")
            return
        }

        recordingJob = serviceScope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBuf > 0) maxOf(minBuf, SAMPLE_RATE * 2) else SAMPLE_RATE * 2

            audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                Timber.e(e, "AudioRecord creation failed")
                null
            }

            if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord failed to initialize")
                broadcastError("Microphone unavailable")
                return@launch
            }

            val allSamples = mutableListOf<Float>()
            val chunk = ShortArray(1024)
            var silenceFrames = 0
            var hasSpoken = false
            val maxSamples = SAMPLE_RATE * MAX_RECORDING_SEC

            try {
                audioRecord?.startRecording()
                Timber.d("Recording started (16kHz mono, dynamic VAD)")

                while (allSamples.size < maxSamples) {
                    val read = audioRecord?.read(chunk, 0, chunk.size) ?: break
                    if (read <= 0) break

                    var sumSq = 0f
                    for (i in 0 until read) {
                        val f = chunk[i] / 32768.0f
                        allSamples.add(f)
                        sumSq += f * f
                    }

                    val rms = kotlin.math.sqrt(sumSq / read)

                    if (rms >= VAD_SPEECH_THRESHOLD) {
                        hasSpoken = true
                        silenceFrames = 0
                    } else if (rms < VAD_SILENCE_THRESHOLD) {
                        if (hasSpoken) {
                            silenceFrames++
                            if (silenceFrames >= VAD_SILENCE_FRAMES && allSamples.size >= MIN_RECORDING_SAMPLES) {
                                Timber.d("VAD silence detected after speech — stopping recording")
                                break
                            }
                        }
                    } else {
                        silenceFrames = 0
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during audio recording")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) { /* ignore */ }
                audioRecord = null
            }

            if (allSamples.isNotEmpty()) {
                Timber.d("Recording complete: ${allSamples.size} samples")
                processAudio(allSamples.toFloatArray())
            }
        }
    }

    private fun stopRecording() {
        mainHandler.post { stopSpeechRecognizer() }
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { /* ignore */ }
        audioRecord = null
    }

    private suspend fun processAudio(samples: FloatArray) {
        val result = whisperEngine.detectAndRoute(samples)
        if (result.text.isBlank()) {
            Timber.w("Audio processing returned blank text. Likely offline without models.")
            broadcastError("Offline models not installed")
        } else {
            broadcastTranscript(result.text, result.languageCode)
        }
    }

    private fun broadcastTranscript(text: String, language: String) {
        val intent = Intent("ai.lumi.TRANSCRIPT").apply {
            setPackage(packageName)
            putExtra("text", text)
            putExtra("language", language)
        }
        sendBroadcast(intent)
    }

    /**
     * Broadcasts a partial (in-progress) transcript to the overlay so the user can see
     * what the speech recognizer is hearing in real time.
     */
    private fun broadcastPartialTranscript(partial: String) {
        val intent = Intent("ai.lumi.TRANSCRIPT_PARTIAL").apply {
            setPackage(packageName)
            putExtra("text", partial)
        }
        sendBroadcast(intent)
    }

    private fun broadcastError(msg: String) {
        val intent = Intent("ai.lumi.VOICE_ERROR").apply {
            setPackage(packageName)
            putExtra("error", msg)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lumi Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Lumi voice assistant"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lumi")
            .setContentText("Listening...")
            .setSmallIcon(R.drawable.ic_lumi_bubble)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording()
        ttsEngine.release()
        whisperEngine.release()
        super.onDestroy()
    }
}
