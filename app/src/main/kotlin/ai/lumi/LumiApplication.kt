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

        // API keys are entered by the user in Settings and stored in SecureKeyStore.

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
