package ai.lumi.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import ai.lumi.R
import ai.lumi.engine.TaskEngine
import ai.lumi.engine.TaskState
import ai.lumi.uimap.UIMapRepository
import javax.inject.Inject

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var taskEngine: TaskEngine
    @Inject lateinit var uiMapRepository: UIMapRepository
    @Inject lateinit var preferences: ai.lumi.data.datastore.LumiPreferences

    private lateinit var windowManager: WindowManager
    private var bubbleView: BubbleView? = null
    private var guidanceBubbleView: GuidanceBubbleView? = null
    private var transcriptView: android.widget.TextView? = null
    private var transcriptParams: WindowManager.LayoutParams? = null
    private var loadingAudioPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var savedX = 100
    private var savedY = 400

    /** Tracks the active language so the Guiding state can render the right instruction. */
    private var currentLanguage: String = "en"

    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val asset = intent.getStringExtra("asset") ?: return
            playPreRecordedAudio(asset)
        }
    }

    private val partialTranscriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val partial = intent.getStringExtra("text") ?: return
            showTranscriptOverlay(partial)
        }
    }

    companion object {
        private const val CHANNEL_ID = "lumi_overlay"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            audioReceiver,
            IntentFilter("ai.lumi.PLAY_AUDIO"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            partialTranscriptReceiver,
            IntentFilter("ai.lumi.TRANSCRIPT_PARTIAL"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Load bundle maps on first start
        serviceScope.launch(Dispatchers.IO) { uiMapRepository.initBundles() }

        // Sync preferred language from preferences
        serviceScope.launch {
            preferences.preferredLanguage.collect { lang ->
                if (!lang.isNullOrBlank()) {
                    currentLanguage = lang
                    Timber.i("OverlayService: active language synced to $lang")
                }
            }
        }

        showBubble()
        observeTaskState()
        Timber.i("OverlayService started")
    }

    private fun showBubble() {
        val params = WindowManager.LayoutParams(
            56.dpToPx(), 56.dpToPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        bubbleView = BubbleView(this).apply {
            setOnClickListener { taskEngine.onBubbleTapped() }
            setOnTouchListener(DragTouchListener(params, windowManager) { x, y ->
                savedX = x; savedY = y
            })
        }
        windowManager.addView(bubbleView, params)
    }

    private fun showGuidanceBubble(instruction: String) {
        if (instruction.isBlank()) {
            hideGuidanceBubble()
            return
        }
        if (guidanceBubbleView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = resources.displayMetrics.heightPixels / 5
            }
            guidanceBubbleView = GuidanceBubbleView(this)
            windowManager.addView(guidanceBubbleView, params)
        }
        guidanceBubbleView?.setInstruction(instruction)
        guidanceBubbleView?.visibility = View.VISIBLE
    }

    private fun hideGuidanceBubble() {
        guidanceBubbleView?.visibility = View.GONE
    }

    /**
     * Interactive floating prompt card at the bottom.
     * Displays real-time pseudo-text as speech is recognized, an enter arrow (➔) to confirm,
     * a close button (✕), and quick task chips for zero-typing execution.
     */
    private var promptCardView: android.view.View? = null
    private var promptTextView: android.widget.TextView? = null
    private var currentSpokenPrompt: String = ""

    private fun showTranscriptOverlay(text: String) {
        val density = resources.displayMetrics.density
        currentSpokenPrompt = text

        if (promptCardView == null) {
            val rootLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xF01A1A2E.toInt())
                    cornerRadius = 20f * density
                    setStroke((1.5f * density).toInt(), 0xFFF5A100.toInt())
                }
                setPadding(
                    (16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt()
                )
            }

            // ── Top Row: [🎤] [Live Speech Text] [➔] [✕] ──
            val topRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Mic Icon
            val micIcon = android.widget.TextView(this).apply {
                this.text = "🎤"
                textSize = 20f
                setPadding(0, 0, (8 * density).toInt(), 0)
            }
            topRow.addView(micIcon)

            // Live pseudo text
            val tv = android.widget.TextView(this).apply {
                this.text = text
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            promptTextView = tv
            topRow.addView(tv)

            // Enter Arrow Button (➔) — user requested: "get auto-prompt when enter arrow is clicked"
            val enterBtn = android.widget.TextView(this).apply {
                this.text = "➔"
                setTextColor(0xFF1A1A2E.toInt())
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF5A100.toInt()) // Lumi Saffron
                    cornerRadius = 18f * density
                }
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((8 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    val promptToSend = currentSpokenPrompt.trim()
                    if (promptToSend.isNotBlank() && promptToSend != "Listening...") {
                        sendTranscriptIntent(promptToSend)
                        hideTranscriptOverlay()
                    }
                }
            }
            topRow.addView(enterBtn)

            // Cancel Button (✕)
            val cancelBtn = android.widget.TextView(this).apply {
                this.text = "✕"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                isClickable = true
                setOnClickListener {
                    taskEngine.cancel()
                    hideTranscriptOverlay()
                }
            }
            topRow.addView(cancelBtn)

            rootLayout.addView(topRow)

            // ── Suggestions Row (Horizontal chips) ──
            val scroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
                layoutParams = lp
            }

            val chipRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }

            val suggestions = if (currentLanguage == "hi") {
                listOf(
                    "wifi chalu karo" to "📶 वाई-फाई",
                    "settings kholo" to "⚙️ सेटिंग्स",
                    "dark mode lagao" to "🌙 डार्क मोड",
                    "bluetooth chalu karo" to "📡 ब्लूटूथ"
                )
            } else {
                listOf(
                    "Turn on Wi-Fi" to "📶 Wi-Fi",
                    "Open Settings" to "⚙️ Settings",
                    "Open Display settings" to "🌙 Dark Mode",
                    "Turn on Bluetooth" to "📡 Bluetooth"
                )
            }

            for ((command, label) in suggestions) {
                val chip = android.widget.TextView(this).apply {
                    this.text = label
                    setTextColor(0xFFF5A100.toInt())
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x33F5A100.toInt())
                        cornerRadius = 12f * density
                        setStroke((1f * density).toInt(), 0x66F5A100.toInt())
                    }
                    setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (8 * density).toInt()
                    }
                    layoutParams = lp
                    isClickable = true
                    setOnClickListener {
                        sendTranscriptIntent(command)
                        hideTranscriptOverlay()
                    }
                }
                chipRow.addView(chip)
            }
            scroll.addView(chipRow)
            rootLayout.addView(scroll)

            promptCardView = rootLayout

            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (80 * density).toInt()
            }
            transcriptParams = params
            try { windowManager.addView(rootLayout, params) } catch (e: Exception) { Timber.e(e, "Failed to add prompt card overlay") }
        }

        promptTextView?.text = if (text == "Listening...") "🎤 Listening... speak now" else text
        promptCardView?.visibility = View.VISIBLE
        promptCardView?.alpha = 1f
    }

    private fun sendTranscriptIntent(text: String) {
        val intent = Intent("ai.lumi.TRANSCRIPT").apply {
            setPackage(packageName)
            putExtra("text", text)
            putExtra("language", currentLanguage)
        }
        sendBroadcast(intent)
    }

    private fun hideTranscriptOverlay() {
        promptCardView?.let { cv ->
            cv.animate().alpha(0f).setDuration(350).withEndAction {
                cv.visibility = View.GONE
            }.start()
        }
    }

    private fun observeTaskState() {
        serviceScope.launch {
            taskEngine.state.collectLatest { state ->
                when (state) {
                    is TaskState.Idle -> {
                        bubbleView?.setState(BubbleView.State.IDLE)
                        hideGuidanceBubble()
                        hideTranscriptOverlay()
                    }
                    is TaskState.Initializing -> {
                        bubbleView?.setState(BubbleView.State.LOADING)
                    }
                    is TaskState.Listening -> {
                        bubbleView?.setState(BubbleView.State.LISTENING)
                        showTranscriptOverlay("Listening...")
                    }
                    is TaskState.Thinking -> {
                        // Capture language from ASR result so Guiding state renders correctly
                        currentLanguage = state.language
                        bubbleView?.setState(BubbleView.State.LOADING)
                        // Keep transcript visible briefly then fade
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            hideTranscriptOverlay()
                        }, 1000)
                    }
                    is TaskState.Guiding -> {
                        bubbleView?.setState(BubbleView.State.GUIDING)
                        // instruction() takes the language code ("en" / "hi"), not the text itself
                        showGuidanceBubble(state.currentStep.instruction(currentLanguage))
                    }
                    is TaskState.Done -> {
                        bubbleView?.setState(BubbleView.State.IDLE)
                        hideGuidanceBubble()
                    }
                    is TaskState.Error -> {
                        bubbleView?.setState(BubbleView.State.ERROR)
                        showGuidanceBubble(state.message)
                    }
                    is TaskState.Recovering -> {
                        bubbleView?.setState(BubbleView.State.LOADING)
                    }
                }
            }
        }
    }

    private fun playPreRecordedAudio(assetName: String) {
        val resId = when (assetName) {
            "audio_chime" -> R.raw.audio_chime
            "audio_checking" -> R.raw.audio_checking
            "audio_moment" -> R.raw.audio_moment
            "audio_cancel" -> R.raw.audio_cancel
            else -> return
        }
        try {
            // Loading clips must not overlap spoken guidance. Stop the previous
            // clip before replacing it; MediaPlayer stop is synchronous and keeps
            // the transition under the 1.5s safety bound.
            loadingAudioPlayer?.let { previous ->
                runCatching { previous.stop() }
                previous.release()
            }
            loadingAudioPlayer = MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener {
                    if (loadingAudioPlayer === this) loadingAudioPlayer = null
                    release()
                }
                start()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play $assetName")
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lumi Assistant", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Lumi overlay"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lumi")
            .setContentText("Tap the bubble to get help with any task")
            .setSmallIcon(R.drawable.ic_lumi_bubble)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loadingAudioPlayer?.release()
        loadingAudioPlayer = null
        unregisterReceiver(audioReceiver)
        try { unregisterReceiver(partialTranscriptReceiver) } catch (e: Exception) { }
        bubbleView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        guidanceBubbleView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        promptCardView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        transcriptView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        super.onDestroy()
    }
}

/** Touch listener that makes a WindowManager view draggable. */
class DragTouchListener(
    private val params: WindowManager.LayoutParams,
    private val wm: WindowManager,
    private val onPositionChanged: (Int, Int) -> Unit
) : View.OnTouchListener {
    private var initX = 0; private var initY = 0
    private var touchX = 0f; private var touchY = 0f
    private var dragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initX = params.x; initY = params.y
                touchX = event.rawX; touchY = event.rawY
                dragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchX).toInt()
                val dy = (event.rawY - touchY).toInt()
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                    dragging = true
                    params.x = initX + dx; params.y = initY + dy
                    wm.updateViewLayout(v, params)
                    onPositionChanged(params.x, params.y)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) v.performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }
}
