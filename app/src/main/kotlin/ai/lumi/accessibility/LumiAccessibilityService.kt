package ai.lumi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import ai.lumi.cursor.CursorView
import ai.lumi.cursor.CursorAnimator
import ai.lumi.engine.TaskEngine
import ai.lumi.engine.TaskState
import javax.inject.Inject

@AndroidEntryPoint
class LumiAccessibilityService : AccessibilityService() {

    @Inject lateinit var taskEngine: TaskEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorAnimator: CursorAnimator? = null
    private lateinit var windowManager: WindowManager

    companion object {
        /** Singleton reference — set in onCreate, cleared in onDestroy. */
        var instance: LumiAccessibilityService? = null
            private set

        /**
         * The most recently captured accessibility root node.
         * Updated on every WINDOW_STATE_CHANGED and WINDOW_CONTENT_CHANGED event.
         * Used by [ai.lumi.engine.TaskEngine.captureUITreeJson] to build the UI tree for cloud inference.
         *
         * NOTE: [android.view.accessibility.AccessibilityNodeInfo] is recycled by the system —
         * only read this on the main thread or ensure you call [obtain] before passing between threads.
         */
        @Volatile
        var latestRoot: AccessibilityNodeInfo? = null
            private set
    }

    private val cursorReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ai.lumi.SHOW_CURSOR" -> {
                    val x = intent.getIntExtra("x", 500)
                    val y = intent.getIntExtra("y", 1000)
                    showCursorAt(Rect(x, y, x + 10, y + 10))
                }
                "ai.lumi.HIDE_CURSOR" -> {
                    hideCursor()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = android.content.IntentFilter().apply {
            addAction("ai.lumi.SHOW_CURSOR")
            addAction("ai.lumi.HIDE_CURSOR")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cursorReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Timber.i("LumiAccessibilityService created")
    }

    private var windowContext: Context? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val ctx = createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                windowContext = ctx
                windowManager = ctx.getSystemService(WindowManager::class.java)
                Timber.i("Initialized windowManager with createWindowContext(TYPE_ACCESSIBILITY_OVERLAY)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create accessibility windowContext, using default WindowManager")
        }

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        // Observe TaskEngine state to show/hide cursor
        serviceScope.launch {
            taskEngine.state.collect { state ->
                handleStateChange(state)
            }
        }
        Timber.i("LumiAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // Ignore Lumi's own windows and system dialogs
                if (pkg == packageName) return
                Timber.d("Screen changed → $pkg")
                // Capture root for UITree inference
                latestRoot = rootInActiveWindow
                taskEngine.onScreenChanged(pkg)
                checkAndReattachCursor()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkg = event.packageName?.toString()
                // Ignore taps within Lumi itself
                if (pkg == packageName) return
                val tappedBounds = Rect().also { bounds -> event.source?.getBoundsInScreen(bounds) }
                    .takeIf { !it.isEmpty }
                taskEngine.onUserInteraction(pkg, tappedBounds)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Refresh root so UITree stays current during content changes
                latestRoot = rootInActiveWindow
                taskEngine.onScreenContentChanged()
                checkAndReattachCursor()
            }
        }
    }

    private var lastFoundRect: Rect? = null
    private var lastCheckTime = 0L
    private var targetWasFound = false
    private var lastNodeCount = 0

    private fun countNodes(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        var count = 1
        for (i in 0 until node.childCount) {
            count += countNodes(node.getChild(i))
        }
        return count
    }

    private fun checkAndReattachCursor() {
        val state = taskEngine.state.value
        if (state !is TaskState.Guiding) return
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 250L) return
        lastCheckTime = now

        val step = state.currentStep
        val targetDesc = step.targetDescription
        if (targetDesc == "Not Found") {
            hideCursor()
            return
        }

        // A TaskEngine-resolved rectangle is authoritative for this guidance step.
        // Re-running a fuzzy launcher-tree text search here used to replace the
        // chosen target with an unrelated icon/container on CONTENT_CHANGED events.
        step.resolvedBounds?.takeIf { !it.isEmpty }?.let { resolved ->
            lastFoundRect = resolved
            targetWasFound = true
            showCursorAt(resolved)
            return
        }

        if (targetDesc.isNotBlank()) {
            val rect = findElementByDescription(targetDesc)
            if (rect != null && !rect.isEmpty) {
                lastFoundRect = rect
                targetWasFound = true
                Timber.i("checkAndReattachCursor: target '$targetDesc' at $rect")
                showCursorAt(rect)
                return
            } else if (targetWasFound) {
                // Target was specified, previously found, but is no longer on screen. Auto-advance.
                Timber.i("checkAndReattachCursor: target '$targetDesc' lost, asking TaskEngine to re-evaluate")
                targetWasFound = false
                taskEngine.onTargetLost()
            }
        } else if (state.totalSteps <= 1) {
            // Coordinate-only VLM target: Detect significant content shifts (e.g. they tapped and screen changed)
            val currentNodes = countNodes(latestRoot)
            if (lastNodeCount > 0) {
                val diff = Math.abs(currentNodes - lastNodeCount)
                if (diff > lastNodeCount * 0.3) {
                    Timber.i("checkAndReattachCursor: massive layout shift detected ($lastNodeCount -> $currentNodes), auto-advancing VLM")
                    lastNodeCount = currentNodes
                    taskEngine.onTargetLost()
                    return
                }
            }
            lastNodeCount = currentNodes
        }

        // If target not resolved by description in current window, maintain at fallback coordinates
        if (cursorContainer == null || cursorView?.isAttachedToWindow != true) {
            val bounds = state.currentStep.resolvedBounds
            if (bounds != null && !bounds.isEmpty) {
                showCursorAt(bounds)
            } else if (state.currentStep.relativeX > 0f && state.currentStep.relativeY > 0f) {
                val dm = resources.displayMetrics
                val x = (state.currentStep.relativeX * dm.widthPixels).toInt()
                val y = (state.currentStep.relativeY * dm.heightPixels).toInt()
                showCursorAt(Rect(x - 20, y - 20, x + 20, y + 20))
            }
        }
    }

    override fun onInterrupt() {
        Timber.w("LumiAccessibilityService interrupted")
    }

    private var cursorContainer: android.widget.FrameLayout? = null
    private var guidanceTextView: android.widget.TextView? = null

    // ── Cursor & Guidance window management ─────────────────────────────────────

    /**
     * Show the cursor at [targetRect] using TYPE_ACCESSIBILITY_OVERLAY.
     * Guaranteed to display over external apps, system settings, and third-party UIs.
     */
    fun showCursorAt(targetRect: Rect) {
        val sizePx = 56.dpToPx()
        val density = resources.displayMetrics.density
        Timber.i("showCursorAt: targetRect=$targetRect, center=(${targetRect.centerX()}, ${targetRect.centerY()})")

        if (cursorContainer == null) {
            try {
                val ctx = windowContext ?: this
                val container = android.widget.FrameLayout(ctx).apply {
                    isClickable = false
                    isFocusable = false
                }

                // 2. Cursor View
                val view = CursorView(ctx)
                val lp = android.widget.FrameLayout.LayoutParams(sizePx, sizePx)
                container.addView(view, lp)

                val overlayType = if (windowContext != null) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                val params = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
                try {
                    windowManager.addView(container, params)
                    Timber.i("Added cursor & guidance container with $overlayType successfully")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to add with $overlayType, attempting TYPE_APPLICATION_OVERLAY fallback")
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    (getSystemService(WINDOW_SERVICE) as WindowManager).addView(container, params)
                    Timber.i("Added cursor & guidance container with TYPE_APPLICATION_OVERLAY fallback successfully")
                }
                cursorContainer = container
                cursorParams = params
                cursorView = view
                cursorAnimator = CursorAnimator(view) { x, y ->
                    cursorParams?.let { p ->
                        // CursorAnimator supplies the overlay's top-left position
                        // after accounting for the arrow-tip hotspot. Do not subtract
                        // it again or the visible tip lands one cursor-width away.
                        p.x = x.toInt()
                        p.y = y.toInt()
                        try {
                            windowManager.updateViewLayout(cursorContainer, p)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to add cursor view to WindowManager")
            }
        }

        cursorAnimator?.animateTo(
            targetX = targetRect.centerX().toFloat(),
            targetY = targetRect.centerY().toFloat(),
            onComplete = { Timber.i("Cursor animation to targetRect completed") }
        )
    }

    fun hideCursor() {
        cursorContainer?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            cursorContainer = null
            cursorView = null
            cursorParams = null
            cursorAnimator = null
            guidanceTextView = null
            lastFoundRect = null
        }
    }

    /**
     * Performs a physical tap via the AccessibilityService API at the specified coordinates.
     */
    fun performAutoTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            val success = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Timber.i("performAutoTap: Auto-tap completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Timber.w("performAutoTap: Auto-tap cancelled at ($x, $y)")
                }
            }, null)
            
            if (!success) {
                Timber.e("performAutoTap: dispatchGesture failed to start")
            }
        } else {
            Timber.w("performAutoTap: Not supported on SDK < 24")
        }
    }

    // ── Accessibility tree queries ──────────────────────────────────────────────

    /** Find element bounds by text content, view ID, or widget type. Returns null if not found. */
    fun findElementByDescription(description: String): Rect? {
        val root = rootInActiveWindow ?: return null
        val lowerDesc = description.lowercase().trim()

        // 1. Direct text search
        val byText = root.findAccessibilityNodeInfosByText(description)
        if (byText.isNotEmpty()) {
            val rect = Rect()
            byText.first().getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }

        // 2. Direct viewId search (exact)
        val byViewId = root.findAccessibilityNodeInfosByViewId(description)
        if (byViewId.isNotEmpty()) {
            val rect = Rect()
            byViewId.first().getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }

        // 3. Traversal search for partial IDs (e.g. "switch_btn" -> "com.android.settings:id/switch_btn")
        // and widget types (e.g. Switch)
        var foundRect: Rect? = null
        val cleanTarget = lowerDesc.replace(Regex("[^a-z0-9\\s]"), "").trim()
        
        traverseNode(root, 0) { node, _ ->
            if (foundRect != null) return@traverseNode
            val id = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val txt = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            val cleanTxt = txt.lowercase().replace(Regex("[^a-z0-9\\s]"), "").trim()
            val cleanNodeDesc = desc.lowercase().replace(Regex("[^a-z0-9\\s]"), "").trim()

            val matchesId = id.endsWith("/$lowerDesc", ignoreCase = true) || id.contains(lowerDesc, ignoreCase = true)
            val matchesText = cleanTxt.contains(cleanTarget) || cleanNodeDesc.contains(cleanTarget) ||
                    txt.contains(description, ignoreCase = true) || desc.contains(description, ignoreCase = true)
            val isSwitchTarget = (lowerDesc.contains("switch") || lowerDesc.contains("toggle")) &&
                    (cls.contains("Switch", ignoreCase = true) || id.contains("switch", ignoreCase = true))

            if (matchesId || matchesText || isSwitchTarget) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty && r.width() > 0 && r.height() > 0) {
                    foundRect = r
                }
            }
        }

        return foundRect
    }

    /** Capture the full Accessibility UI tree as a flat JSON string. */
    fun captureUITree(): String {
        val root = rootInActiveWindow ?: return "{}"
        return buildString {
            append("{\"nodes\":[")
            var first = true
            traverseNode(root, 0) { node, depth ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!first) append(",")
                first = false
                append("""{"d":$depth,"text":${escapeJson(node.text?.toString())},"desc":${escapeJson(node.contentDescription?.toString())},"id":${escapeJson(node.viewIdResourceName)},"cls":${escapeJson(node.className?.toString())},"b":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}],"click":${node.isClickable}}""")
            }
            append("]}")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, depth: Int, visitor: (AccessibilityNodeInfo, Int) -> Unit) {
        visitor(node, depth)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNode(it, depth + 1, visitor) }
        }
    }

    private fun escapeJson(value: String?): String =
        if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    // ── State-driven UI ─────────────────────────────────────────────────────────

    private fun handleStateChange(state: TaskState) {
        Timber.i("handleStateChange: $state")
        when (state) {
            is TaskState.Guiding -> {
                targetWasFound = false
                if (state.currentStep.targetDescription == "Not Found") {
                    Timber.i("Target is 'Not Found', hiding cursor (instruction only)")
                    hideCursor()
                    return
                }
                val bounds = state.currentStep.resolvedBounds
                if (bounds != null && !bounds.isEmpty) {
                    Timber.i("Showing cursor at resolved bounds: $bounds")
                    showCursorAt(bounds)
                } else if (state.currentStep.relativeX > 0f && state.currentStep.relativeY > 0f) {
                    val dm = resources.displayMetrics
                    val x = (state.currentStep.relativeX * dm.widthPixels).toInt()
                    val y = (state.currentStep.relativeY * dm.heightPixels).toInt()
                    Timber.i("Showing cursor at relative coordinates ($x, $y)")
                    showCursorAt(Rect(x - 20, y - 20, x + 20, y + 20))
                } else {
                    Timber.w("No bounds or relative coords for step: ${state.currentStep}")
                }
            }
            is TaskState.Idle, is TaskState.Done, is TaskState.Error -> {
                Timber.i("Hiding cursor due to state: $state")
                hideCursor()
            }
            else -> { /* no cursor change during thinking/listening */ }
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { unregisterReceiver(cursorReceiver) } catch (e: Exception) { }
        hideCursor()
        instance = null
        super.onDestroy()
    }
}
