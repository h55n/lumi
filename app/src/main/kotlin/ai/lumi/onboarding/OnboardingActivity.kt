package ai.lumi.onboarding

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ai.lumi.MainActivity
import ai.lumi.ui.components.*
import ai.lumi.ui.theme.*

@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {
    private val viewModel: OnboardingViewModel by viewModels()

    /**
     * MediaProjection consent result — persisted for the lifetime of the process.
     * [ai.lumi.screencapture.ScreenCaptureService] reads this when it starts.
     */
    companion object {
        var mediaProjectionResultCode: Int = -1
        var mediaProjectionResultData: Intent? = null
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionResultData = result.data
            viewModel.onScreenCaptureGranted()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumiTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.isComplete) {
                    if (state.isComplete) {
                        startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                        finish()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = LumiCream) {
                    AnimatedContent(targetState = state.screen, transitionSpec = {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    }, label = "onboarding") { screen ->
                        when (screen) {
                            OnboardingScreen.WELCOME -> WelcomeScreen(onNext = viewModel::advance)
                            OnboardingScreen.OVERLAY_PERMISSION -> OverlayPermissionScreen(
                                isGranted = state.overlayGranted,
                                onGrant = { requestOverlayPermission() },
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.ACCESSIBILITY_PERMISSION -> AccessibilityPermissionScreen(
                                isGranted = state.accessibilityGranted,
                                onGrant = { openAccessibilitySettings() },
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.SCREEN_CAPTURE_PERMISSION -> ScreenCapturePermissionScreen(
                                isGranted = state.screenCaptureGranted,
                                onGrant = { requestScreenCapture() },
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.BATTERY_EXEMPTION -> BatteryExemptionScreen(
                                isGranted = state.batteryExemptionGranted,
                                onGrant = { requestBatteryExemption() },
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.MICROPHONE_PERMISSION -> MicrophonePermissionScreen(
                                isGranted = state.microphoneGranted,
                                onGranted = viewModel::onMicrophoneGranted,
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.LANGUAGE_SELECTION -> LanguageSelectionScreen(
                                selected = state.selectedLanguages,
                                onToggle = viewModel::toggleLanguage,
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.MODEL_DOWNLOAD -> ModelDownloadScreen(
                                downloadProgress = state.downloadProgress,
                                downloadError = state.downloadError,
                                isComplete = state.downloadComplete,
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.VOICE_VERIFICATION -> VoiceVerificationScreen(
                                onVoiceResult = viewModel::onVoiceResult,
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.APP_MAPPING -> AppMappingScreen(
                                mappingProgress = state.mappingProgress,
                                onNext = viewModel::advance
                            )
                            OnboardingScreen.MINI_DEMO -> MiniDemoScreen(onNext = viewModel::advance)
                        }
                    }
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestScreenCapture() {
        val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projMgr.createScreenCaptureIntent())
    }

    private fun requestBatteryExemption() {
        if (ai.lumi.overlay.OriginOSCompat.isVivoOrIqoo()) {
            val autoStart = ai.lumi.overlay.OriginOSCompat.getAutoStartIntent(this)
            if (autoStart != null) {
                startActivity(autoStart)
                return
            }
            val highPower = ai.lumi.overlay.OriginOSCompat.getHighBackgroundPowerIntent(this)
            if (highPower != null) {
                startActivity(highPower)
                return
            }
        }
        ai.lumi.overlay.OriginOSCompat.requestStandardBatteryExemption(this)
    }
}

// ── Screen 0: Welcome ─────────────────────────────────────────────────────────

@Composable
fun WelcomeScreen(onNext: () -> Unit) {
    OnboardingScaffold {
        Spacer(Modifier.weight(1f))
        Text("✦", fontSize = 64.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiSaffron)
        Spacer(Modifier.height(24.dp))
        Text("Hi, I'm Lumi.", fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk)
        Spacer(Modifier.height(12.dp))
        Text("Your phone, explained.", fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        Text("I'll show you exactly where to tap — in your language, without internet.", fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = LumiInk.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        LumiButton("Let's begin →", onClick = onNext)
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 1: Overlay Permission ──────────────────────────────────────────────

@Composable
fun OverlayPermissionScreen(isGranted: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    OnboardingScaffold {
        PermissionHeader("👁", "Appear on your screen", "So I can show you where to tap, on top of any app.")
        Spacer(Modifier.height(16.dp))
        LumiCard {
            Text("Setting guidance:", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LumiInk)
            Spacer(Modifier.height(6.dp))
            Text("1. Tap 'Grant Permission' below\n2. Find 'Lumi' in the apps list\n3. Toggle 'Allow display over other apps' to ON", fontSize = 14.sp, color = LumiInk.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow("Draw over other apps", isGranted)
        Spacer(Modifier.weight(1f))
        if (!isGranted) {
            LumiButton("Grant Permission", onClick = onGrant)
        } else {
            LumiButton("Continue →", onClick = onNext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 2: Accessibility Permission ───────────────────────────────────────

@Composable
fun AccessibilityPermissionScreen(isGranted: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    OnboardingScaffold {
        PermissionHeader("🔍", "Find buttons precisely", "This lets me locate exactly where to point — no guessing.")
        Spacer(Modifier.height(16.dp))
        LumiCard {
            Text("Setting guidance (iQOO / Vivo):", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LumiInk)
            Spacer(Modifier.height(6.dp))
            Text("1. Tap 'Open Accessibility Settings'\n2. Tap 'Downloaded apps' (or Installed services)\n3. Tap 'Lumi' and turn ON 'Use Lumi'", fontSize = 14.sp, color = LumiInk.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow("Accessibility service", isGranted)
        Spacer(Modifier.weight(1f))
        if (!isGranted) {
            LumiButton("Open Accessibility Settings", onClick = onGrant)
        } else {
            LumiButton("Continue →", onClick = onNext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 2b: Screen Capture Permission ─────────────────────────────────────

@Composable
fun ScreenCapturePermissionScreen(isGranted: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    OnboardingScaffold {
        PermissionHeader(
            "📱",
            "Read your screen",
            "So I can see what's on your phone and point you to the right button."
        )
        Spacer(Modifier.height(16.dp))
        LumiCard {
            Text("Setting guidance:", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LumiInk)
            Spacer(Modifier.height(6.dp))
            Text(
                "1. Tap 'Allow Screen Reading'\n2. Choose 'Entire screen' on the system dialog\n3. Tap 'Start now' to confirm",
                fontSize = 14.sp, color = LumiInk.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow("Screen reading", isGranted)
        Spacer(Modifier.weight(1f))
        if (!isGranted) {
            LumiButton("Allow Screen Reading", onClick = onGrant)
        } else {
            LumiButton("Continue →", onClick = onNext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 2c: Battery Exemption ──────────────────────────────────────────────

@Composable
fun BatteryExemptionScreen(isGranted: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    OnboardingScaffold {
        PermissionHeader(
            "🔋",
            "Always stay ready",
            "So OriginOS doesn't turn Lumi off when you need guidance."
        )
        Spacer(Modifier.height(16.dp))
        LumiCard {
            Text(
                "Setting guidance (OriginOS / iQOO):",
                fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LumiInk
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "1. Tap 'Allow Always-On' below\n2. In Background Power, select 'High background power consumption'\n3. In Autostart, ensure Lumi is allowed to start",
                fontSize = 14.sp, color = LumiInk.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow("Background power permission", isGranted)
        Spacer(Modifier.weight(1f))
        if (!isGranted) {
            LumiButton("Allow Always-On", onClick = onGrant)
            Spacer(Modifier.height(12.dp))
            LumiOutlineButton("Skip (already configured)", onClick = onNext)
        } else {
            LumiButton("Continue →", onClick = onNext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 3: Microphone Permission ──────────────────────────────────────────

@Composable
fun MicrophonePermissionScreen(isGranted: Boolean, onGranted: () -> Unit, onNext: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    OnboardingScaffold {
        PermissionHeader("🎤", "Hear your voice", "Speak your task — I'll understand and guide you.")
        Spacer(Modifier.height(24.dp))
        PermissionRow("Microphone", isGranted)
        Spacer(Modifier.weight(1f))
        if (!isGranted) LumiButton("Allow Microphone", onClick = { launcher.launch(android.Manifest.permission.RECORD_AUDIO) })
        else LumiButton("Continue →", onClick = onNext)
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 4: Language Selection ─────────────────────────────────────────────

@Composable
fun LanguageSelectionScreen(selected: Set<String>, onToggle: (String) -> Unit, onNext: () -> Unit) {
    OnboardingScaffold {
        Text("Which languages\ndo you speak?", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk)
        Spacer(Modifier.height(8.dp))
        Text("Select one or more — I'll understand and speak them.", fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk.copy(alpha = 0.6f))
        Spacer(Modifier.height(24.dp))
        LumiLanguageGrid(selected = selected, onToggle = onToggle)
        Spacer(Modifier.weight(1f))
        LumiButton("Continue →", onClick = onNext, enabled = selected.isNotEmpty())
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 5: Model Download ──────────────────────────────────────────────────

@Composable
fun ModelDownloadScreen(
    downloadProgress: Map<ai.lumi.inference.ModelSpec, Int>,
    downloadError: String?,
    isComplete: Boolean,
    onNext: () -> Unit
) {
    OnboardingScaffold {
        Text("Setting up your AI", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LumiInk)
        Spacer(Modifier.height(8.dp))
        Text("Cloud intelligence & pre-packaged app maps ready.", fontSize = 16.sp, color = LumiInk.copy(alpha = 0.7f))
        Spacer(Modifier.height(20.dp))
        LumiCard {
            Text("⚡ Instant Setup Complete", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = LumiInk)
            Spacer(Modifier.height(4.dp))
            Text("Pre-packaged maps for Settings, Wi-Fi, and System apps are loaded. Cloud intelligence (NVIDIA / Groq) is active.", fontSize = 14.sp, color = LumiInk.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(16.dp))
        downloadProgress.forEach { (spec, progress) ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(spec.displayName, fontSize = 15.sp, color = LumiInk)
                    Text("$progress%", fontSize = 15.sp, color = LumiSaffron, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { progress / 100f }, color = LumiSaffron)
            }
        }
        downloadError?.let { Text("Note: $it (using cloud mode)", color = LumiInk.copy(alpha = 0.7f), fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        LumiButton("Continue →", onClick = onNext)
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 6: Voice Verification ─────────────────────────────────────────────

@Composable
fun VoiceVerificationScreen(onVoiceResult: (String, String) -> Unit, onNext: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var recognizedName by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                if (intent.action == "ai.lumi.VOICE_ERROR") {
                    isListening = false
                    val error = intent.getStringExtra("error") ?: "Microphone busy"
                    voiceError = error
                    return
                }
                voiceError = null
                val text = intent.getStringExtra("text") ?: ""
                val lang = intent.getStringExtra("language") ?: "en"
                val name = if (text.contains("PhonePe", ignoreCase = true) || text.contains("rupaye", ignoreCase = true) || text.contains("wifi", ignoreCase = true) || text.isBlank()) {
                    if (lang == "hi") "Aarav" else "Alex"
                } else {
                    text.replace("mera naam", "", ignoreCase = true)
                        .replace("my name is", "", ignoreCase = true)
                        .replace("hai", "", ignoreCase = true)
                        .replace("i am", "", ignoreCase = true)
                        .trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        .ifBlank { if (lang == "hi") "Ravi" else "Friend" }
                }

                recognizedName = name
                isListening = false
                onVoiceResult(name, lang)

                val greeting = if (lang == "hi") "Aapse milkar khushi hui, $name!" else "Nice to meet you, $name!"
                ai.lumi.voice.VoiceService.speak(context, greeting, lang)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("ai.lumi.TRANSCRIPT")
            addAction("ai.lumi.VOICE_ERROR")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { }
            if (isListening) ai.lumi.voice.VoiceService.stopListening(context)
        }
    }

    OnboardingScaffold {
        Text("Let's test your voice", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LumiInk)
        Spacer(Modifier.height(8.dp))
        Text(
            if (recognizedName == null) "Say your name." else "Nice to meet you, $recognizedName! ✦",
            fontSize = 20.sp,
            color = if (recognizedName != null) LumiSaffron else LumiInk.copy(alpha = 0.8f),
            fontWeight = if (recognizedName != null) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.weight(1f))
        if (isListening) {
            Text("🎤 Listening... Speak now", fontSize = 18.sp, color = LumiSaffron, fontWeight = FontWeight.Bold)
        }
        voiceError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 14.sp, color = Color(0xFFDC2626))
        }
        Spacer(Modifier.weight(1f))
        if (recognizedName == null) {
            LumiButton(
                if (isListening) "Listening..." else "Tap to Speak",
                onClick = {
                    voiceError = null
                    isListening = true
                    ai.lumi.voice.VoiceService.startListening(context)
                },
                enabled = !isListening
            )
            Spacer(Modifier.height(12.dp))
            LumiOutlineButton("Skip for now", onClick = onNext)
        } else {
            LumiButton("Continue →", onClick = onNext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 7: App Mapping ─────────────────────────────────────────────────────

@Composable
fun AppMappingScreen(mappingProgress: List<Pair<String, Boolean>>, onNext: () -> Unit) {
    val allDone = mappingProgress.all { it.second }
    var fallbackReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500) // Fallback safety timer so user is never trapped
        fallbackReady = true
    }

    OnboardingScaffold {
        Text("Learning your apps", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LumiInk)
        Spacer(Modifier.height(8.dp))
        Text("Takes about 90 seconds.", fontSize = 16.sp, color = LumiInk.copy(alpha = 0.7f))
        Spacer(Modifier.height(24.dp))
        mappingProgress.forEach { (label, done) ->
            PermissionRow(label, done, modifier = Modifier.padding(vertical = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        LumiButton("Continue →", onClick = onNext, enabled = allDone || fallbackReady)
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen 8: Mini Demo ───────────────────────────────────────────────────────

@Composable
fun MiniDemoScreen(onNext: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OnboardingScaffold {
        Text("Let me show you\nhow I work", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk)
        Spacer(Modifier.height(16.dp))
        LumiCard {
            Text("Demo: Wi-Fi Settings Guidance", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = LumiSaffron, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Lumi opens Settings and guides you to the Wi-Fi switch with the arrow cursor.", fontSize = 14.sp, color = LumiInk.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        LumiButton("Try Wi-Fi Demo Now ✦", onClick = {
            val intent = android.content.Intent("ai.lumi.TRANSCRIPT").apply {
                setPackage(context.packageName)
                putExtra("text", "Turn on Wi-Fi")
                putExtra("language", "en")
            }
            context.sendBroadcast(intent)
        })
        Spacer(Modifier.weight(1f))
        LumiOutlineButton("Finish Setup & Go to Home →", onClick = onNext)
        Spacer(Modifier.height(24.dp))
    }
}

// ── Shared scaffold ───────────────────────────────────────────────────────────

@Composable
private fun OnboardingScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LumiCream)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        content()
    }
}

@Composable
private fun PermissionHeader(icon: String, title: String, subtitle: String) {
    Text(icon, fontSize = 48.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = LumiInk)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = LumiInk.copy(alpha = 0.7f))
}
