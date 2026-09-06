package ai.lumi.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.lumi.accessibility.ElementFinder
import ai.lumi.cloud.AnthropicClient
import ai.lumi.data.datastore.LumiPreferences
import ai.lumi.inference.InferenceResult
import ai.lumi.inference.MoondreamEngine
import ai.lumi.inference.ModelSelector
import ai.lumi.inference.VLMEngine
import ai.lumi.screencapture.ScreenCaptureService
import ai.lumi.uimap.UIMapRepository
import ai.lumi.voice.TTSEngine
import ai.lumi.voice.VoiceService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core orchestrator for the guidance loop.
 *
 * FLOW:
 *   bubble tap → [onBubbleTapped]
 *   → play loading audio → start recording
 *   → transcript arrives via broadcast → [onTranscriptReceived]
 *   → check bundle/cache → or run VLM
 *   → [deliverStep] → cursor + voice
 *   → AccessibilityEvent (screen changed) → [onScreenChanged]
 *   → next step → repeat
 *   → task complete → [onTaskComplete]
 *
 * All UI state is emitted via [state] StateFlow.
 * OverlayService observes this to drive BubbleView and CursorView.
 */
@Singleton
class TaskEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMapRepository: UIMapRepository,
    private val vlmEngine: VLMEngine,
    private val moondreamEngine: MoondreamEngine,
    private val ttsEngine: TTSEngine,
    private val elementFinder: ElementFinder,
    private val taskClassifier: TaskClassifier,
    private val appInventory: AppInventory,
    private val modelSelector: ModelSelector,
    private val preferences: LumiPreferences,
    private val anthropicClient: AnthropicClient,
    private val modelDownloadManager: ai.lumi.inference.ModelDownloadManager,
    private val actionTemplateEngine: ActionTemplateEngine,
    private val elementMatcher: ElementMatcher,
    private val tapEvaluator: TapEvaluator,
    private val screenshotBuffer: ScreenshotBuffer
) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile private var guidanceVersion = 0L

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    
    private val activeNodeMap = mutableMapOf<Int, Rect>()

    // Active task context
    private var currentLanguage: String = "en"
    private var currentGoal: String = ""
    private var currentGoalWithContext: String = ""
    private var currentPlan: List<TaskStep> = emptyList()
    private var goalPackageName: String? = null
    private var currentStepIndex: Int = 0
    private var currentPackageName: String? = null
    private val stepHistory = mutableListOf<String>()

    private var lastTargetDescription: String? = null
    private var sameTargetCount: Int = 0

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "ai.lumi.TRANSCRIPT") {
                val text = intent.getStringExtra("text") ?: return
                val lang = intent.getStringExtra("language") ?: "en"
                engineScope.launch { onTranscriptReceived(text, lang) }
            } else if (intent.action == "ai.lumi.VOICE_ERROR") {
                val error = intent.getStringExtra("error") ?: "Error"
                Timber.e("VoiceService error: $error")
                _state.value = TaskState.Idle
                VoiceService.stopListening(context)
                engineScope.launch { ttsEngine.speak(error, currentLanguage) }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction("ai.lumi.TRANSCRIPT")
            addAction("ai.lumi.VOICE_ERROR")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        engineScope.launch {
            preferences.preferredLanguage.collect { lang ->
                if (!lang.isNullOrBlank()) {
                    currentLanguage = lang
                    Timber.i("TaskEngine: active language synced to $lang")
                }
            }
        }

        engineScope.launch {
            try {
                appInventory.restoreFromPreferences()
            } catch (e: Exception) {
                Timber.w(e, "AppInventory restore failed")
            }
        }

        engineScope.launch {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            
            if (isWifi) {
                ai.lumi.inference.ModelSpec.entries.filter { it.isDirectDownloadable }.forEach { spec ->
                    if (!modelDownloadManager.isDownloaded(spec)) {
                        Timber.i("Auto-downloading missing model: ${spec.displayName}")
                        try {
                            modelDownloadManager.download(spec, allowCellular = false).collect {}
                        } catch (e: Exception) {
                            Timber.e(e, "Auto-download failed for ${spec.displayName}")
                        }
                    }
                }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /** Called when the user taps the floating bubble. */
    fun onBubbleTapped() {
        if (_state.value is TaskState.Guiding) {
            cancel()
            return
        }
        engineScope.launch { startListeningFlow() }
    }

    /** Called by LumiAccessibilityService when the foreground app changes. */
    private var lastInteractionTime = 0L
    private var typingJob: Job? = null
    private var transitionJob: Job? = null
    private var pendingDemoContact: String? = null

    fun onScreenChanged(newPackageName: String?) {
        if (newPackageName == "ai.lumi") return // Ignore our own UI
        
        val isKeyboard = newPackageName?.contains("inputmethod") == true || newPackageName?.contains("keyboard") == true
        if (isKeyboard) return // Ignore keyboard popups

        if (newPackageName != null) currentPackageName = newPackageName

        // Dedicated local contact demo route. Once Contacts has rendered, its
        // accessibility rows already contain the exact name and bounds; do not
        // send the new screen to a model before moving the cursor.
        if (pendingDemoContact != null && newPackageName?.contains("contact", ignoreCase = true) == true) {
            val contact = pendingDemoContact ?: return
            transitionJob?.cancel()
            transitionJob = engineScope.launch {
                delay(60)
                if (pendingDemoContact == contact) resolveDemoContact(contact)
            }
            return
        }

        if (_state.value is TaskState.Guiding) scheduleScreenRefresh("window")
    }

    /** Called for dynamic screens that update content without changing package/window. */
    fun onScreenContentChanged() {
        if (_state.value is TaskState.Guiding) scheduleScreenRefresh("content")
    }

    private fun scheduleScreenRefresh(source: String) {
        // A new live tree supersedes any pending/in-flight cloud analysis. Cancelling
        // it is essential: otherwise a slow response puts a first-screen cursor
        // back after the user has already reached screen two.
        guidanceVersion++
        transitionJob?.cancel()
        Timber.i("$source changed; refreshing guidance in 80ms")
        transitionJob = engineScope.launch {
            delay(80)
            reEvaluateCurrentScreen()
        }
    }

    fun getCurrentLanguage(): String = currentLanguage

    /** Called when the user taps (AccessibilityEvent.TYPE_VIEW_CLICKED). */
    fun onUserInteraction(tappedPackage: String? = null, tappedBounds: Rect? = null) {
        val now = System.currentTimeMillis()
        if (now - lastInteractionTime < 400L) return // Debounce multiple rapid taps
        lastInteractionTime = now

        val isKeyboard = tappedPackage?.contains("inputmethod") == true || tappedPackage?.contains("keyboard") == true

        if (_state.value is TaskState.Guiding) {
            val guidedBounds = (_state.value as TaskState.Guiding).currentStep.resolvedBounds
            if (guidedBounds != null && tappedBounds != null &&
                !guidedBounds.contains(tappedBounds.centerX(), tappedBounds.centerY())) {
                Timber.i("onUserInteraction: ignored off-target tap at $tappedBounds; expected $guidedBounds")
                engineScope.launch { ttsEngine.speak(correctionAudio(currentLanguage), currentLanguage) }
                return
            }
            // Ignore launcher/search taps when we have a concrete goal app
            if (tappedPackage != null && !isKeyboard) {
                if (appInventory.isLauncherOrSearch(tappedPackage) &&
                    goalPackageName != null &&
                    goalPackageName != tappedPackage
                ) {
                    Timber.d("onUserInteraction: ignoring launcher/search tap in $tappedPackage (goal=$goalPackageName)")
                    return
                }
                val isLauncherOrSysUi = tappedPackage == "com.android.launcher3" ||
                        tappedPackage == "com.android.systemui" ||
                        tappedPackage == "com.vivo.upslide" ||
                        tappedPackage == "com.vivo.card" ||
                        tappedPackage == "com.vivo.globalsearch"
                if (isLauncherOrSysUi && currentPackageName != null && currentPackageName != tappedPackage) {
                    Timber.d("onUserInteraction: ignoring tap in $tappedPackage while guiding for $currentPackageName")
                    return
                }
            }

            if (isKeyboard) {
                Timber.i("onUserInteraction: user is typing. Debouncing advance for 3 seconds.")
                typingJob?.cancel()
                typingJob = engineScope.launch {
                    delay(3000)
                    advanceToNextStep()
                }
                return
            }

            Timber.i("onUserInteraction: validated tap in $tappedPackage; advancing in 80ms")
            typingJob?.cancel()
            transitionJob?.cancel()
            guidanceVersion++ // Prevent an older cloud result from restoring a stale step.
            transitionJob = engineScope.launch {
                // Do not wait for screenshot hashes or a complete spoken
                // correction; both made a successful tap feel unresponsive.
                delay(80)
                advanceToNextStep()
            }
        }
    }

    /** Called when the currently guided target element disappears from the screen (e.g. screen changed). */
    fun onTargetLost() {
        val now = System.currentTimeMillis()
        if (now - lastInteractionTime < 1000L) return // Debounce

        if (_state.value is TaskState.Guiding) {
            Timber.i("onTargetLost: target is no longer on screen. Advancing step to re-evaluate.")
            lastInteractionTime = now
            transitionJob?.cancel()
            transitionJob = engineScope.launch {
                advanceToNextStep()
            }
        }
    }

    /** Cancel the current task and return to Idle. */
    fun cancel() {
        activeJob?.cancel()
        transitionJob?.cancel()
        guidanceVersion++
        ttsEngine.stop()
        context.sendBroadcast(Intent("ai.lumi.HIDE_CURSOR").apply { setPackage(context.packageName) })
        _state.value = TaskState.Idle
        VoiceService.stopListening(context)
        stepHistory.clear()
        currentPlan = emptyList()
        currentStepIndex = 0
        engineScope.launch { ttsEngine.speak(cancelAudio(currentLanguage), currentLanguage) }
    }

    // ── Private flow ────────────────────────────────────────────────────────────

    private suspend fun startListeningFlow() {
        _state.value = TaskState.Initializing
        // Never leave spoken loading audio playing when the microphone opens;
        // otherwise SpeechRecognizer transcribes Lumi's own "checking screen"
        // message as the user's request.
        playPreRecordedAudio(AUDIO_CHIME)
        delay(100)
        _state.value = TaskState.Listening
        VoiceService.startListening(context)
    }

    private suspend fun onTranscriptReceived(text: String, language: String) {
        // This build is Hindi-first for the live demo. It also covers the
        // AudioRecord fallback, whose recognizer may report English for Hinglish.
        val interactionLanguage = "hi"

        // Demo-critical contact route: "mujhe Hassan ka contact chahiye" opens
        // Contacts directly and finds Hassan from the live tree. It deliberately
        // bypasses network, screenshots and generic task classification.
        requestedDemoContact(text)?.let { contact ->
            currentLanguage = interactionLanguage
            currentGoal = text
            currentGoalWithContext = text
            guidanceVersion++
            pendingDemoContact = contact
            _state.value = TaskState.Thinking(text, interactionLanguage)
            if (launchContactsApp()) {
                // Some OEM Contacts apps reuse the existing task and emit no
                // WINDOW_STATE_CHANGED event. Resolve independently of that
                // event so the demo never remains stuck in Thinking.
                transitionJob?.cancel()
                transitionJob = engineScope.launch {
                    delay(180)
                    if (pendingDemoContact == contact) resolveDemoContact(contact)
                }
                return
            }
            pendingDemoContact = null
            _state.value = TaskState.Error("कॉन्टैक्ट्स ऐप नहीं खुल पाया।", interactionLanguage)
            return
        }
        // Ensure the installed app inventory is populated before classification.
        // This is a fast no-op if already scanned; blocks only on the very first
        // invocation (typically <200ms). Prevents "app not found → search fallback".
        if (!appInventory.isScanned()) {
            try { appInventory.ensureScanned() } catch (e: Exception) { Timber.w(e, "AppInventory ensureScanned non-fatal") }
        }

        val systemMatch = IntentLibrary.classify(text)
        if (systemMatch != null && systemMatch.confidence >= 0.7f) {
            IntentLibrary.execute(context, systemMatch, ttsEngine, interactionLanguage)
            _state.value = TaskState.Idle
            return
        }

        currentGoal = text
        currentLanguage = interactionLanguage
        guidanceVersion++
        stepHistory.clear()

        Timber.i("Goal: '$text' [$currentLanguage]")
        _state.value = TaskState.Thinking(text, currentLanguage)

        val taskType = taskClassifier.classifyWithConfidence(text).type
        val pkg = taskClassifier.resolveTargetPackage(text, taskType)
            ?: taskClassifier.packageForTask(taskType)
            ?: currentPackageName
            ?: ""
            
        goalPackageName = pkg
        
        currentGoalWithContext = if (pkg.isNotBlank() && pkg != currentPackageName) {
            val appLabel = appInventory.getCached().firstOrNull { it.packageName == pkg }?.label
            if (!appLabel.isNullOrBlank()) {
                "$text (Hint: Target App is $appLabel)"
            } else {
                text
            }
        } else {
            text
        }

        // UI maps are app-version-specific. Validate them for every task so an
        // updated bundled app cannot reuse coordinates/labels from an old UI.
        if (pkg.isNotBlank()) uiMapRepository.checkStaleness(pkg)

        // Auto-launch target app if not a system settings task and we're not in it
        when (taskType) {
            TaskType.SETTINGS_WIFI, TaskType.SETTINGS_DISPLAY, TaskType.SETTINGS_BLUETOOTH, TaskType.SETTINGS_MAIN -> {
                // Do not auto-launch settings; guide step-by-step from current screen
            }
            else -> {
                // NOTE: Auto-launch has been disabled so the system can guide the user
                // step-by-step from the home screen using the VLM natively.
            }
        }

        // Try direct screen plan for system settings tasks
        var cached: List<TaskStep>? = when (taskType) {
            TaskType.SETTINGS_WIFI -> uiMapRepository.getBundledPlanForScreen("com.android.settings", "Settings_WiFi")
            TaskType.SETTINGS_DISPLAY -> uiMapRepository.getBundledPlanForScreen("com.android.settings", "Settings_Display")
            TaskType.SETTINGS_MAIN -> uiMapRepository.getBundledPlanForScreen("com.android.settings", "Settings_Main")
            else -> null
        }

        // Action Template Engine (PRD Section 3.2): Match-first deterministic recipes
        if (cached == null && pkg.isNotBlank()) {
            // NOTE: Deterministic templates have been bypassed so the system always
            // falls back to the VLM, ensuring proper multilingual TTS responses and
            // dynamic step-by-step guidance rather than hardcoded jumps.
        }

        // Only take screenshot now — right before we need it for inference.
        // This ensures the screenshot reflects the CURRENT screen state at guidance time
        // and avoids capturing a stale frame taken during or before the user's speech.
        var screenshot: Bitmap? = null

        // Try bundle / Room DB with pHash if not resolved
        if (cached == null && pkg.isNotBlank()) {
            screenshot = takeScreenshot()
            if (screenshot != null) cached = uiMapRepository.findGuidancePlan(screenshot, pkg)
        }

        // Fallback for bundled demo apps if pHash did not match closely
        if (cached == null && pkg.isNotBlank()) {
            val bundledFallback = uiMapRepository.getInitialBundledPlan(pkg)
            if (bundledFallback != null) {
                Timber.d("Using bundled plan fallback for $pkg")
                cached = bundledFallback
            }
        }

        if (cached != null) {
            Timber.d("Bundle/cache hit for $pkg — no VLM needed")
            currentPlan = cached
            currentStepIndex = 0
            deliverStep()
        } else {
            // Run VLM inference — take screenshot now if not already taken
            if (screenshot == null) screenshot = takeScreenshot()
            Timber.d("No cache — running VLM")
            runVLMAndGuide(screenshot, currentGoalWithContext, currentLanguage)
        }
    }

    private suspend fun runVLMAndGuide(screenshot: Bitmap?, goal: String, language: String) {
        val requestVersion = guidanceVersion
        // Schedule "one moment" audio after 2s
        val momentJob = engineScope.launch {
            delay(2000)
            if (_state.value is TaskState.Thinking) playPreRecordedAudio(AUDIO_MOMENT)
        }

        // Contacts are a common demo flow and their rows are exposed reliably by
        // AccessibilityService. Resolve an explicitly spoken contact locally so
        // opening Contacts immediately moves to the person instead of waiting on
        // a cloud model to rediscover the screen.
        fastVisibleContactStep(goal)?.let { step ->
            currentPlan = listOf(step)
            currentStepIndex = 0
            deliverStep()
            return
        }

        // ── Priority 1: Groq/Mistral/NIM via UITree (no screenshot needed, faster & more reliable) ──
        val metrics = context.resources.displayMetrics
        val uiTreeJson = captureUITreeJson()
        var result: InferenceResult = if (uiTreeJson.isNotBlank()) {
            try {
                Timber.i("Attempting UITree cloud guidance via Groq/Mistral/NIM (app=$currentPackageName)")
                anthropicClient.analyseWithUITree(
                    uiTreeJson, goal, language, metrics.widthPixels, metrics.heightPixels,
                    currentApp = currentPackageName ?: "",
                    stepHistory = stepHistory.toList()
                )
            } catch (e: Exception) {
                Timber.e(e, "UITree cloud guidance threw")
                InferenceResult.error(e.message ?: "Cloud error", language)
            }
        } else {
            InferenceResult.error("No UI tree available", language)
        }

        // ── Priority 2: Screenshot-based on-device VLM ──
        if (result.isError) {
            if (screenshot == null) {
                momentJob.cancel()
                _state.value = TaskState.Error("Could not read screen", language)
                return
            }
            Timber.i("UITree guidance failed — falling back to on-device VLM")
            val tier = modelSelector.configure()
            result = try {
                if (tier.supportsFlagshipVLM) {
                    vlmEngine.analyse(screenshot, goal, language, stepHistory)
                } else {
                    moondreamEngine.analyse(screenshot, goal, language)
                }
            } catch (e: Exception) {
                Timber.e(e, "On-device VLM inference failed")
                InferenceResult.error(e.message ?: "Inference error", language)
            }
        }

        // ── Priority 3: Screenshot-based cloud VLM (Anthropic/Gemini via analyse()) ──
        if (result.isError && screenshot != null) {
            Timber.i("On-device VLM failed — falling back to cloud screenshot analysis")
            result = try {
                anthropicClient.analyse(screenshot, goal, language)
            } catch (e: Exception) {
                Timber.e(e, "Cloud screenshot analysis also failed")
                InferenceResult.error(e.message ?: "Cloud error", language)
            }
        }

        momentJob.cancel()

        // Any newer tap/task has a newer view of the screen. Discard this result
        // instead of putting the cursor back on the previous screen's target.
        if (requestVersion != guidanceVersion) {
            Timber.i("Discarding stale guidance result (request=$requestVersion current=$guidanceVersion)")
            return
        }

        if (result.isError) {
            val isLikelyOffline = result.errorMessage?.contains("Exception", ignoreCase = true) == true || result.errorMessage?.contains("error", ignoreCase = true) == true
            val finalError = if (isLikelyOffline) "Offline models not installed. Please connect to Wi-Fi to use cloud AI or download local models." else result.errorMessage ?: "Error"
            Timber.e("VLM fully failed. Reporting error: $finalError")
            _state.value = TaskState.Error(finalError, language)
            return
        }
        if (result.isTaskComplete) {
            onTaskComplete()
            return
        }

        if (result.isStuck) {
            Timber.e("VLM reported it is stuck. Aborting task.")
            val errorMsg = when (currentLanguage) {
                "hi" -> "Mujhe samajh nahin aa raha, kripya khud try karein."
                "ta" -> "Puriyavillai, neengalae muyarchi seyyungal."
                "te" -> "Artham kaavaleni, meeru mee antaga chesukoni."
                "bn" -> "Ami atkhe gechi, apni nijee cheshtaa korun."
                "mr" -> "Mala samajat nahi, tumhich kara."
                else -> "I am stuck and cannot proceed."
            }
            _state.value = TaskState.Error(errorMsg, language)
            return
        }

        if (result.targetDescription == lastTargetDescription && result.targetDescription != "Not Found" && result.targetDescription.isNotBlank()) {
            sameTargetCount++
        } else {
            lastTargetDescription = result.targetDescription
            sameTargetCount = 1
        }

        if (sameTargetCount == 2) {
            Timber.w("Loop detected on target '${result.targetDescription}'. Clearing plan and adding strict constraint.")
            currentPlan = emptyList()
            stepHistory.add("[SYSTEM MESSAGE] You are stuck repeatedly tapping '${result.targetDescription}' but it is not progressing the workflow. You MUST choose a different element or different action. DO NOT tap '${result.targetDescription}' again.")
        } else if (sameTargetCount == 3) {
            Timber.w("Second loop detected on target '${result.targetDescription}'.")
            currentPlan = emptyList()
            stepHistory.add("[SYSTEM MESSAGE] You have failed to progress and are stuck. You MUST set status='stuck' and tell the user in the instruction why you cannot proceed (in their language).")
        }
        if (sameTargetCount >= 4) {
            Timber.e("Stuck in a loop on '${result.targetDescription}'. Aborting task.")
            val errorMsg = when (currentLanguage) {
                "hi" -> "Mujhe samajh nahin aa raha, kripya khud try karein."
                "ta" -> "Puriyavillai, neengalae muyarchi seyyungal."
                "te" -> "Artham kaavaleni, meeru mee antaga chesukoni."
                "bn" -> "Ami aatkhe gechi, apni nijee cheshtaa korun."
                "mr" -> "Mala samajat nahi, tumhich kara."
                else -> "I am stuck and cannot proceed."
            }
            _state.value = TaskState.Error(errorMsg, currentLanguage)
            return
        }

        // Build single-step plan from VLM result and continue
        val step = TaskStep(
            stepIndex = currentStepIndex,
            targetId = result.targetId,
            targetDescription = result.targetDescription,
            instructionEn = if (currentLanguage == "en") result.instruction else "",
            instructionNative = localizedInstruction(result.instruction, currentLanguage),
            nativeLanguage = currentLanguage,
            relativeX = result.relativeX,
            relativeY = result.relativeY,
            wait = result.wait
        )
        currentPlan = listOf(step)
        currentStepIndex = 0
        deliverStep()
    }

    private suspend fun deliverStep() {
        val step = currentPlan.getOrNull(currentStepIndex) ?: run {
            onTaskComplete()
            return
        }

        // ── Priority 1: Direct activeNodeMap lookup by targetId (UITree path) ──────────────
        // When the cloud model returns a target_id from the live accessibility tree,
        // those bounds are EXACT — use them immediately without any further tree search
        // which could match a different (wrong) element.
        val nodeMapBounds = if (step.targetId != null && activeNodeMap.containsKey(step.targetId)) {
            val rawBounds = activeNodeMap[step.targetId]
            
            // SECURITY/ACCURACY CHECK: Launcher folders/containers often report as single clickable 
            // nodes that cover half the screen. If the bounds are massive, the model probably 
            // selected the container instead of the specific item. Let the fallbacks find the 
            // exact item by name instead of pointing at the center of a giant container.
            if (rawBounds != null && !rawBounds.isEmpty) {
                val metrics = context.resources.displayMetrics
                val isTooLarge = (rawBounds.width() > metrics.widthPixels * 0.8f) && 
                                 (rawBounds.height() > metrics.heightPixels * 0.4f)
                if (isTooLarge) {
                    Timber.w("deliverStep: targetId=${step.targetId} bounds $rawBounds are too large (container). Falling back to label search.")
                    null // Reject container bounds
                } else {
                    rawBounds // Good specific bounds
                }
            } else null
        } else null

        if (nodeMapBounds != null && !nodeMapBounds.isEmpty) {
            Timber.i("deliverStep: targetId=${step.targetId} resolved directly from nodeMap → $nodeMapBounds")
            val resolvedStep = step.copy(resolvedBounds = nodeMapBounds)
            _state.value = TaskState.Guiding(resolvedStep, currentStepIndex, currentPlan.size)
            val instruction = resolvedStep.instruction(currentLanguage)
            stepHistory.add(instruction)
            ttsEngine.speak(instruction, currentLanguage)
            return
        }

        // ── Priority 2: ElementMatcher — live tree search by label/description ────────────
        var bounds: Rect? = null
        if (step.targetDescription.isNotBlank()) {
            val root = ai.lumi.accessibility.LumiAccessibilityService.instance?.rootInActiveWindow
            if (root != null) {
                val labels = (step.labelHints + listOf(step.targetDescription)).distinct().filter { it.isNotBlank() }
                val resIds = (step.resourceIdHints + listOf(step.targetDescription)).distinct().filter { it.isNotBlank() }
                val intent = ParsedIntent(
                    actionType = step.action,
                    entityLabels = labels,
                    entityResourceIds = resIds
                )
                val match = elementMatcher.findBestMatch(root, intent)
                if (match != null) {
                    // Similar container check for ElementMatcher matches
                    val metrics = context.resources.displayMetrics
                    val isTooLarge = (match.bounds.width() > metrics.widthPixels * 0.8f) && 
                                     (match.bounds.height() > metrics.heightPixels * 0.4f)
                    
                    if (!isTooLarge) {
                        bounds = match.bounds
                        Timber.i("deliverStep: ElementMatcher resolved '${step.targetDescription}' → $bounds")
                    } else {
                        Timber.w("deliverStep: ElementMatcher bounds too large, skipping")
                    }
                }
            }
        }

        // ── Priority 3: ElementFinder — text search + relativeX/Y fallback ────────────────
        if ((bounds == null || bounds.isEmpty) && step.targetDescription.isNotBlank()) {
            bounds = elementFinder.find(
                description = step.targetDescription,
                relativeX = step.relativeX,
                relativeY = step.relativeY
            )
        }

        // ── Priority 4: relativeX/Y from VLM screenshot response ──────────────────────────
        // Coordinates from a screenshot model are only a safe last resort when no
        // accessibility tree is available. If a live tree exists but cannot resolve
        // the model's named target, it is a hallucinated/stale target (for example
        // "Contacts" on a launcher page without Contacts); do not point at an
        // unrelated icon merely because the model also emitted coordinates.
        val hasLiveTree = ai.lumi.accessibility.LumiAccessibilityService.instance
            ?.rootInActiveWindow != null
        if (bounds == null || bounds.isEmpty) {
            val mayUseCoordinates = !hasLiveTree || step.targetDescription.isBlank()
            if (mayUseCoordinates && step.relativeX in 0.001f..1f && step.relativeY in 0.001f..1f) {
                val dm = context.resources.displayMetrics
                val x = (step.relativeX * dm.widthPixels).toInt()
                val y = (step.relativeY * dm.heightPixels).toInt()
                bounds = Rect(x - 20, y - 20, x + 20, y + 20)
                Timber.i("deliverStep: using relativeX/Y fallback → $bounds")
            }
        }

        // A missing tree target and no valid coords → re-read screen rather than placing
        // cursor at (0,0) or screen center.
        if (bounds == null || bounds.isEmpty) {
            _state.value = TaskState.Recovering
            ttsEngine.speak(recoveryAudio(currentLanguage), currentLanguage)
            reEvaluateCurrentScreen()
            return
        }
        val resolvedStep = step.copy(resolvedBounds = bounds)
        _state.value = TaskState.Guiding(resolvedStep, currentStepIndex, currentPlan.size)

        // Speak instruction
        val instruction = resolvedStep.instruction(currentLanguage)
        stepHistory.add(instruction)
        ttsEngine.speak(instruction, currentLanguage)

        // Auto-tap logic
        // Lumi is guidance-only. Keep interaction user-initiated, particularly on
        // financial and account screens; the visible cursor never performs taps.
    }

    private suspend fun advanceToNextStep() {
        currentStepIndex++
        when {
            currentStepIndex < currentPlan.size -> {
                deliverStep()
            }
            else -> {
                // A generic click, step count, or window event is not a success
                // signal. Continue live evaluation until a terminal result is found.
                _state.value = TaskState.Thinking(currentGoal, currentLanguage)
                val screenshot = takeScreenshot()
                runVLMAndGuide(screenshot, currentGoalWithContext, currentLanguage)
            }
        }
    }

    private suspend fun onTaskComplete() {
        Timber.i("onTaskComplete: goal='$currentGoal'")
        _state.value = TaskState.Done(currentLanguage)
        val msg = localizedCompletion(currentLanguage)
        ttsEngine.speak(msg, currentLanguage)
        delay(2000)
        _state.value = TaskState.Idle
        stepHistory.clear()
    }

    private fun takeScreenshot(): Bitmap? =
        ScreenCaptureService.latestFrame

    private fun fastVisibleContactStep(goal: String): TaskStep? {
        val root = ai.lumi.accessibility.LumiAccessibilityService.instance?.rootInActiveWindow ?: return null
        val ignored = setOf("call", "dial", "phone", "contact", "contacts", "karo", "kar", "ko", "open", "kholo", "please", "करो", "कॉल", "फोन", "कॉन्टैक्ट")
        val candidates = goal.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in ignored }
            .distinct()

        for (candidate in candidates) {
            val node = root.findAccessibilityNodeInfosByText(candidate).firstOrNull { item ->
                item.isVisibleToUser && (
                    item.text?.toString()?.equals(candidate, ignoreCase = true) == true ||
                    item.contentDescription?.toString()?.equals(candidate, ignoreCase = true) == true
                )
            } ?: continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.isEmpty) {
                Timber.i("Fast contact path resolved '$candidate' at $bounds")
                return TaskStep(
                    stepIndex = 0,
                    targetDescription = candidate,
                    instructionEn = "Tap $candidate",
                    instructionNative = "$candidate पर टैप करें।",
                    nativeLanguage = "hi",
                    resolvedBounds = bounds
                )
            }
        }
        return null
    }

    private suspend fun resolveDemoContact(contact: String) {
        repeat(8) {
            val step = fastVisibleContactStep(contact)
            if (step != null) {
                pendingDemoContact = null
                currentPlan = listOf(step)
                currentStepIndex = 0
                deliverStep()
                return
            }
            delay(75)
        }
        // Do not silently fall back to cloud after the fast route: that is what
        // caused the multi-second lag and stale "Contacts" instruction.
        _state.value = TaskState.Error("Hassan नहीं मिला। कृपया Contacts सूची में देखें।", currentLanguage)
    }

    private fun requestedDemoContact(text: String): String? {
        val normalized = text.lowercase()
        val asksForContact = listOf("contact", "contacts", "कॉन्टैक्ट", "number", "नंबर", "call", "कॉल")
            .any(normalized::contains)
        return if (asksForContact && normalized.contains("hassan")) "Hassan" else null
    }

    private fun launchContactsApp(): Boolean {
        val packages = listOf(
            "com.vivo.contacts", "com.android.contacts", "com.google.android.contacts",
            "com.vivo.dialer", "com.google.android.dialer"
        )
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Timber.i("Fast contact path launched $pkg")
                return true
            } catch (e: Exception) {
                Timber.w(e, "Could not launch $pkg for contact route")
            }
        }
        return false
    }

    /**
     * Captures the current accessibility node tree and serializes it to compact JSON for cloud inference.
     * Returns an empty string if the accessibility service is unavailable.
     */
    private fun captureUITreeJson(): String {
        val root = ai.lumi.accessibility.LumiAccessibilityService.latestRoot ?: return ""
        activeNodeMap.clear()
        return try {
            val sb = StringBuilder("[")
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 150) {
                val node = queue.removeFirst()
                val text = node.text?.toString()?.take(80) ?: ""
                val desc = node.contentDescription?.toString()?.take(80) ?: ""
                val rid = node.viewIdResourceName?.removePrefix(context.packageName)?.take(60) ?: ""
                val cls = node.className?.toString()?.substringAfterLast('.')?.take(30) ?: ""
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                val clickable = node.isClickable
                if (text.isNotBlank() || desc.isNotBlank() || clickable) {
                    val idx = count
                    activeNodeMap[idx] = bounds
                    if (count > 0) sb.append(",")
                    sb.append("""{"idx":$idx,"t":${jsonStr(text)},"d":${jsonStr(desc)},"id":${jsonStr(rid)},"cls":${jsonStr(cls)},"b":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}],"c":$clickable}""")
                    count++
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            sb.append("]")
            sb.toString()
        } catch (e: Exception) {
            Timber.w(e, "captureUITreeJson failed")
            ""
        }
    }

    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun playPreRecordedAudio(assetName: String) {
        // Delegate to OverlayService's raw audio player
        // Raw files: R.raw.audio_chime, R.raw.audio_checking, R.raw.audio_moment, R.raw.audio_cancel
        val intent = Intent("ai.lumi.PLAY_AUDIO").apply {
            setPackage(context.packageName)
            putExtra("asset", assetName)
        }
        context.sendBroadcast(intent)
    }

    private fun cancelAudio(language: String) = when (language) {
        "hi" -> "Theek hai, ruk jaata hoon."
        "ta" -> "Sari, nirthukirein."
        "te" -> "Sare, aagipotunna."
        "bn" -> "Thik ache, thামছি।"
        "mr" -> "Theek ahe, thorambto."
        "gu" -> "Thik chhe, roki raho chu."
        "pa" -> "Theek hai, ruk jaanda haan."
        "ml" -> "Ente, nirthukunnu."
        "kn" -> "Sari, nillisutte."
        else -> "Okay, I stopped."
    }

    private fun recoveryAudio(language: String) = when (language) {
        "hi" -> "Main aapki screen phir se dekh raha hoon."
        else -> "Let me look at your screen again."
    }

    private fun correctionAudio(language: String) = when (language) {
        "hi" -> "वह नहीं, कृपया दिखाए गए विकल्प पर टैप करें।"
        else -> "Not that one. Please tap the highlighted option."
    }

    private fun normalizeLanguage(language: String): String = language.substringBefore('-').lowercase()

    /** Never surface an English model reply inside a Hindi conversation. */
    private fun localizedInstruction(instruction: String, language: String): String {
        if (language != "hi") return instruction
        return if (instruction.any { it in '\u0900'..'\u097F' }) instruction
        else "इस विकल्प पर टैप करें।"
    }

    private suspend fun reEvaluateCurrentScreen() {
        _state.value = TaskState.Thinking(currentGoal, currentLanguage)
        runVLMAndGuide(takeScreenshot(), currentGoalWithContext, currentLanguage)
    }

    private fun localizedCompletion(language: String) = when (language) {
        "hi" -> "Kaam ho gaya!"
        "ta" -> "Mudindhachu!"
        "te" -> "Pani ayindi!"
        "bn" -> "Kাজ হয়ে গেছে!"
        "mr" -> "Kaam zale!"
        "gu" -> "Kaam thai gayu!"
        "pa" -> "Kaam ho gaya!"
        "ml" -> "Pravsam theernu!"
        "kn" -> "Kelasa aayitu!"
        else -> "Done! Task complete."
    }

    companion object {
        const val AUDIO_CHIME = "audio_chime"
        const val AUDIO_CHECKING = "audio_checking"
        const val AUDIO_MOMENT = "audio_moment"
        const val AUDIO_CANCEL = "audio_cancel"

        val SENSITIVE_APPS_BLOCKLIST = setOf(
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm",
            "com.sbi.SBIFreedomPlus",
            "com.csam.icici.bank.imobile"
        )
    }
}
