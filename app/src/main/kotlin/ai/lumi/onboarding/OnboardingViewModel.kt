package ai.lumi.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import ai.lumi.data.datastore.LumiPreferences
import ai.lumi.inference.ModelDownloadManager
import ai.lumi.inference.ModelSelector
import ai.lumi.inference.ModelSpec
import ai.lumi.engine.AppInventory
import ai.lumi.memory.ProfileManager
import ai.lumi.uimap.BundleMapLoader
import ai.lumi.uimap.UIMapWorker
import javax.inject.Inject

enum class OnboardingScreen {
    WELCOME,
    OVERLAY_PERMISSION,
    ACCESSIBILITY_PERMISSION,
    SCREEN_CAPTURE_PERMISSION,
    BATTERY_EXEMPTION,
    MICROPHONE_PERMISSION,
    LANGUAGE_SELECTION,
    MODEL_DOWNLOAD,
    VOICE_VERIFICATION,
    APP_MAPPING,
    MINI_DEMO
}

data class OnboardingState(
    val screen: OnboardingScreen = OnboardingScreen.WELCOME,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val screenCaptureGranted: Boolean = false,
    val batteryExemptionGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val selectedLanguages: Set<String> = setOf("en"),
    val selectedLanguage: String? = "en",
    val downloadProgress: Map<ModelSpec, Int> = emptyMap(),
    val downloadError: String? = null,
    val downloadComplete: Boolean = false,
    val userName: String = "",
    val isListening: Boolean = false,
    val mappingProgress: List<Pair<String, Boolean>> = emptyList(),
    val isComplete: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: LumiPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelSelector: ModelSelector,
    private val profileManager: ProfileManager,
    private val bundleMapLoader: BundleMapLoader,
    private val appInventory: AppInventory,
    private val deviceScannerService: ai.lumi.engine.DeviceScannerService
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val overlay = Settings.canDrawOverlays(context)
        val accessibility = ai.lumi.overlay.OriginOSCompat.isAccessibilityServiceEnabled(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val battery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val screenCap = OnboardingActivity.mediaProjectionResultCode == android.app.Activity.RESULT_OK

        _state.value = _state.value.copy(
            overlayGranted = overlay,
            accessibilityGranted = accessibility,
            batteryExemptionGranted = battery,
            microphoneGranted = mic,
            screenCaptureGranted = screenCap
        )
    }

    fun advance() {
        refreshPermissions()
        val current = _state.value
        val canAdvance = when (current.screen) {
            OnboardingScreen.WELCOME -> true
            OnboardingScreen.OVERLAY_PERMISSION -> current.overlayGranted
            OnboardingScreen.ACCESSIBILITY_PERMISSION -> current.accessibilityGranted
            OnboardingScreen.SCREEN_CAPTURE_PERMISSION -> current.screenCaptureGranted
            // Battery exemption: always allow advance on OriginOS/iQOO since the API
            // returns false even when the user has correctly configured background power.
            OnboardingScreen.BATTERY_EXEMPTION -> true
            OnboardingScreen.MICROPHONE_PERMISSION -> current.microphoneGranted
            OnboardingScreen.LANGUAGE_SELECTION -> current.selectedLanguages.isNotEmpty()
            OnboardingScreen.MODEL_DOWNLOAD -> true
            OnboardingScreen.VOICE_VERIFICATION -> true
            OnboardingScreen.APP_MAPPING -> true
            OnboardingScreen.MINI_DEMO -> true
        }
        if (!canAdvance) {
            Timber.w("Cannot advance: ${current.screen} conditions not satisfied")
            return
        }

        val next = when (current.screen) {
            OnboardingScreen.WELCOME -> nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.OVERLAY_PERMISSION)
            OnboardingScreen.OVERLAY_PERMISSION -> nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.ACCESSIBILITY_PERMISSION)
            OnboardingScreen.ACCESSIBILITY_PERMISSION -> nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.SCREEN_CAPTURE_PERMISSION)
            OnboardingScreen.SCREEN_CAPTURE_PERMISSION -> nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.BATTERY_EXEMPTION)
            OnboardingScreen.BATTERY_EXEMPTION -> OnboardingScreen.MICROPHONE_PERMISSION
            OnboardingScreen.MICROPHONE_PERMISSION -> OnboardingScreen.LANGUAGE_SELECTION
            OnboardingScreen.LANGUAGE_SELECTION -> OnboardingScreen.MODEL_DOWNLOAD
            OnboardingScreen.MODEL_DOWNLOAD -> OnboardingScreen.VOICE_VERIFICATION
            OnboardingScreen.VOICE_VERIFICATION -> OnboardingScreen.APP_MAPPING
            OnboardingScreen.APP_MAPPING -> OnboardingScreen.MINI_DEMO
            OnboardingScreen.MINI_DEMO -> { completeOnboarding(); return }
        }
        _state.value = _state.value.copy(screen = next)
        onScreenEntered(next)
    }

    /**
     * If [defaultScreen]'s permission is already granted, skip forward to the next screen that
     * isn't yet satisfied — ensuring users never see a permission screen twice after the first run.
     */
    private fun nextUngrantedPermissionScreenOrDefault(
        current: OnboardingState,
        defaultScreen: OnboardingScreen
    ): OnboardingScreen {
        return when (defaultScreen) {
            OnboardingScreen.OVERLAY_PERMISSION ->
                if (current.overlayGranted) nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.ACCESSIBILITY_PERMISSION)
                else OnboardingScreen.OVERLAY_PERMISSION
            OnboardingScreen.ACCESSIBILITY_PERMISSION ->
                if (current.accessibilityGranted) nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.SCREEN_CAPTURE_PERMISSION)
                else OnboardingScreen.ACCESSIBILITY_PERMISSION
            OnboardingScreen.SCREEN_CAPTURE_PERMISSION ->
                if (current.screenCaptureGranted) nextUngrantedPermissionScreenOrDefault(current, OnboardingScreen.BATTERY_EXEMPTION)
                else OnboardingScreen.SCREEN_CAPTURE_PERMISSION
            // Battery exemption: always skip on OriginOS — API returns false even when granted
            OnboardingScreen.BATTERY_EXEMPTION ->
                if (ai.lumi.overlay.OriginOSCompat.isVivoOrIqoo()) OnboardingScreen.MICROPHONE_PERMISSION
                else if (current.batteryExemptionGranted) OnboardingScreen.MICROPHONE_PERMISSION
                else OnboardingScreen.BATTERY_EXEMPTION
            else -> defaultScreen
        }
    }

    private fun onScreenEntered(screen: OnboardingScreen) {
        when (screen) {
            OnboardingScreen.MODEL_DOWNLOAD -> startDownloads()
            OnboardingScreen.APP_MAPPING -> startMapping()
            else -> {}
        }
    }

    fun onOverlayGranted() { refreshPermissions() }
    fun onAccessibilityGranted() { refreshPermissions() }
    fun onScreenCaptureGranted() { _state.value = _state.value.copy(screenCaptureGranted = true) }
    fun onBatteryExemptionGranted() { refreshPermissions() }
    fun onMicrophoneGranted() { refreshPermissions() }

    fun toggleLanguage(code: String) {
        val current = _state.value.selectedLanguages.toMutableSet()
        if (current.contains(code)) {
            if (current.size > 1) current.remove(code)
        } else {
            current.add(code)
        }
        _state.value = _state.value.copy(
            selectedLanguages = current,
            selectedLanguage = current.firstOrNull()
        )
        viewModelScope.launch {
            preferences.setPreferredLanguages(current)
        }
    }

    fun selectLanguage(code: String) {
        toggleLanguage(code)
    }

    fun onVoiceResult(name: String, language: String) {
        _state.value = _state.value.copy(userName = name)
        viewModelScope.launch {
            profileManager.setName(name)
            profileManager.setLanguage(language)
        }
    }

    private fun startDownloads() {
        val tier = modelSelector.configure()
        val required = modelSelector.requiredModels(tier, _state.value.selectedLanguages)
        viewModelScope.launch {
            preferences.setDeviceTier(tier)
            // First load bundle maps (instant — from APK assets)
            bundleMapLoader.loadAll()
            _state.value = _state.value.copy(downloadComplete = true)
            preferences.setModelsDownloaded(true)

            required.forEach { spec ->
                if (modelDownloadManager.isDownloaded(spec)) {
                    _state.value = _state.value.copy(
                        downloadProgress = _state.value.downloadProgress + (spec to 100)
                    )
                    return@forEach
                }
                try {
                    modelDownloadManager.download(spec, allowCellular = true).collect { downloadState ->
                        when (downloadState) {
                            is ai.lumi.inference.DownloadState.Downloading ->
                                _state.value = _state.value.copy(
                                    downloadProgress = _state.value.downloadProgress + (spec to downloadState.progressPercent)
                                )
                            is ai.lumi.inference.DownloadState.Complete ->
                                _state.value = _state.value.copy(
                                    downloadProgress = _state.value.downloadProgress + (spec to 100)
                                )
                            is ai.lumi.inference.DownloadState.Failed ->
                                Timber.w("Optional model download non-fatal: ${downloadState.reason}")
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Background model download non-fatal")
                }
            }
        }
    }

    private fun startMapping() {
        // Show placeholder items while the real scan runs
        val placeholders = listOf("Settings", "Wi-Fi & Networks", "Display & Dark Mode", "Camera", "Clock & Alarms")
        _state.value = _state.value.copy(mappingProgress = placeholders.map { it to false })
        viewModelScope.launch {
            // Bundle maps already loaded — mark bundled apps done immediately
            val updated = placeholders.mapIndexed { i, label ->
                label to (i < 3) // System settings screens are bundled
            }.toMutableList()
            _state.value = _state.value.copy(mappingProgress = updated.toList())

            // ── REAL SCAN: populate AppInventory cache ──────────────────────
            // This ensures that when the user speaks their first goal, the
            // app cache is populated and TaskClassifier can resolve packages
            // without falling back to VLM -> Google search.
            try {
                val scanned = appInventory.ensureScanned(force = true)
                Timber.i("OnboardingViewModel: scanned ${scanned.size} installed apps into AppInventory cache")
            } catch (e: Exception) {
                Timber.e(e, "AppInventory scan during onboarding failed (non-fatal)")
            }
            // ───────────────────────────────────────────────────────────────

            try {
                // Trigger deep scan to completely map all apps pixel by pixel
                deviceScannerService.startDeepScan()
                
                // Enqueue background mapping for runtime apps safely
                UIMapWorker.enqueueInstallMapping(context)
            } catch (e: Exception) {
                Timber.e(e, "Scanner/UIMapWorker background enqueue non-fatal")
            }

            // Smoothly complete remaining items so user is never stuck
            for (i in 3 until updated.size) {
                kotlinx.coroutines.delay(450)
                updated[i] = updated[i].first to true
                _state.value = _state.value.copy(mappingProgress = updated.toList())
            }
        }
    }

    private fun completeOnboarding() {
        viewModelScope.launch {
            preferences.setOnboardingComplete(true)
            _state.value = _state.value.copy(isComplete = true)
        }
    }
}
