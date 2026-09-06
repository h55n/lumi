package ai.lumi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class LumiApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        ai.lumi.cloud.SecureKeyStore.init(this)

        // Pre-load hackathon API keys on first run
        // These are only written if the slot is currently empty (manual Settings override is preserved)
        with(ai.lumi.cloud.SecureKeyStore) {
            if (getGroqKey().isNullOrBlank())
                setGroqKey("REDACTED_API_KEY")
            if (getNvidiaKey().isNullOrBlank())
                setNvidiaKey("REDACTED_API_KEY")
            if (getMistralKey().isNullOrBlank())
                setMistralKey("REDACTED_API_KEY")
        }

        if (BuildConfig.DEBUG || BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Lumi started — your phone, explained. Providers: ${ai.lumi.cloud.SecureKeyStore.getActiveProviders()}")
        val whisperReady = ai.lumi.voice.WhisperJNI.loadLibrary()
        Timber.i("Whisper ASR native library: ready=$whisperReady")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
