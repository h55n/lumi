package ai.lumi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ai.lumi.onboarding.OnboardingActivity
import ai.lumi.overlay.OverlayService
import ai.lumi.ui.theme.LumiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val snackbarHostState = SnackbarHostState()

    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            OnboardingActivity.mediaProjectionResultCode = result.resultCode
            OnboardingActivity.mediaProjectionResultData = result.data
            ai.lumi.screencapture.ScreenCaptureService.start(this, result.resultCode, result.data!!)
        }
    }

    private fun requestScreenCaptureConsent() {
        if (OnboardingActivity.mediaProjectionResultData != null && OnboardingActivity.mediaProjectionResultCode != -1) {
            ai.lumi.screencapture.ScreenCaptureService.start(
                this,
                OnboardingActivity.mediaProjectionResultCode,
                OnboardingActivity.mediaProjectionResultData!!
            )
        } else {
            val projMgr = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
            projMgr?.let {
                mediaProjectionLauncher.launch(it.createScreenCaptureIntent())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep splash screen while loading state
        splashScreen.setKeepOnScreenCondition { !viewModel.isReady.value }

        lifecycleScope.launch {
            viewModel.startupEvent.collect { event ->
                when (event) {
                    StartupEvent.NeedsOnboarding -> {
                        startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                        finish()
                    }
                    StartupEvent.Ready -> {
                        // Screen capture is requested only when a feature actually needs it.
                        // Requesting it at every launch repeatedly interrupts the user.
                        OverlayService.start(this@MainActivity)
                        showHomeScreen()
                    }
                    StartupEvent.AccessibilityServiceDown -> {
                        // Show a non-blocking snackbar — do NOT restart onboarding
                        snackbarHostState.showSnackbar(
                            message = "Lumi accessibility service stopped",
                            actionLabel = "Re-enable"
                        )
                    }
                }
            }
        }

        setContent {
            LumiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) { data ->
                            Snackbar(
                                action = {
                                    TextButton(onClick = {
                                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        startActivity(intent)
                                    }) { Text(data.visuals.actionLabel ?: "") }
                                }
                            ) { Text(data.visuals.message) }
                        }
                    }
                }
            }
        }
    }

    private fun showHomeScreen() {
        setContent {
            LumiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ai.lumi.ui.HomeScreen(
                        onOpenSettings = {
                            startActivity(Intent(this, ai.lumi.settings.SettingsActivity::class.java))
                        },
                        onStopLumi = {
                            OverlayService.stop(this)
                            ai.lumi.screencapture.ScreenCaptureService.stop(this)
                            ai.lumi.voice.VoiceService.stopListening(this)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityServiceHealth(this)
        ai.lumi.overlay.OriginOSCompat.ensureOverlayServiceAlive(this)
    }
}
