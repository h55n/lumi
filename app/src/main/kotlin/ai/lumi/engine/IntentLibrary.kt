package ai.lumi.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import timber.log.Timber
import ai.lumi.voice.TTSEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * System-level intent bypass layer.
 *
 * ONLY intercepts intents that are truly atomic (single OS action, zero UI navigation).
 *
 * Everything else (call without number, settings, WhatsApp, PhonePe, WiFi, camera etc.)
 * returns null so TaskEngine can guide the user step-by-step through the app UI.
 *
 * Rule: if the user needs to *navigate* any app UI after the intent fires -> DO NOT intercept here.
 */
enum class SystemIntent {
    DIRECT_CALL,   // Only when a 10-digit phone number is present
    OPEN_DIALER,
    CHECK_BATTERY,
    FLASHLIGHT,
    ALARM,
    TIMER,
    CALCULATOR,
    OPEN_APP       // Pure 'open X' with no guidance verb
}

data class SystemIntentMatch(
    val intent: SystemIntent,
    val extractedParam: String?,
    val confidence: Float
)

object IntentLibrary {

    fun classify(utterance: String): SystemIntentMatch? {
        val lower = utterance.lowercase().trim()

        // DIRECT CALL - only if a 10-digit phone number is present
        val phoneNumber = extractPhoneNumber(lower)
        if (phoneNumber != null && matchesAny(lower, listOf("call", "dial", "ring", "phone", "karo", "lagao"))) {
            return SystemIntentMatch(SystemIntent.DIRECT_CALL, phoneNumber, 0.95f)
        }

        // A bare call request is a predictable fast-demo route: open the dialer
        // immediately and let the user choose/contact-search themselves.
        if (matchesAny(lower, listOf("call", "dial", "phone", "कॉल", "फोन"))) {
            return SystemIntentMatch(SystemIntent.OPEN_DIALER, null, 0.9f)
        }

        // BATTERY CHECK
        if (matchesAny(lower, listOf("battery kitni", "charge kitni", "battery check", "battery level"))) {
            return SystemIntentMatch(SystemIntent.CHECK_BATTERY, null, 0.9f)
        }

        // FLASHLIGHT
        val flashlightState = extractFlashlightState(lower)
        if (flashlightState != null) {
            return SystemIntentMatch(SystemIntent.FLASHLIGHT, flashlightState, 0.95f)
        }

        // ALARM
        if (matchesAny(lower, listOf("alarm lagao", "alarm set karo", "alarm laga do", "set alarm", "wake me up", "alarm set"))) {
            return SystemIntentMatch(SystemIntent.ALARM, null, 0.9f)
        }

        // TIMER
        if (matchesAny(lower, listOf("timer lagao", "timer set karo", "timer set", "start timer"))) {
            val duration = extractDurationSeconds(lower)
            return SystemIntentMatch(SystemIntent.TIMER, duration, 0.9f)
        }

        // CALCULATOR
        if (matchesAny(lower, listOf("calculator", "hisaab karo", "calculation karo", "calc kholo"))) {
            return SystemIntentMatch(SystemIntent.CALCULATOR, null, 0.9f)
        }

        // OPEN APP - direct launch keeps common demo actions off the slow cloud path.
        val hasGuidanceVerb = matchesAny(lower, listOf(
            "bhejo", "send", "pay", "payment", "message", "msg", "book",
            "ticket", "transfer", "paise", "rupaye", "help", "karna", "kaise"
        ))
        if (!hasGuidanceVerb) {
            if (matchesAny(lower, listOf("open", "kholo", "launch", "chalu karo", "start karo"))) {
                val appName = extractAppName(lower)
                if (appName != null) {
                    return SystemIntentMatch(SystemIntent.OPEN_APP, appName, 0.75f)
                }
            }
        }

        // Everything else -> null -> TaskEngine step-by-step guidance
        return null
    }

    fun execute(context: Context, match: SystemIntentMatch, ttsEngine: TTSEngine, language: String) {
        Timber.i("Executing atomic system intent: ${match.intent} param=${match.extractedParam}")
        try {
            when (match.intent) {
                SystemIntent.DIRECT_CALL -> {
                    val number = match.extractedParam ?: return
                    val cleaned = number.replace(" ", "").replace("-", "")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    speak(ttsEngine, if (language == "hi") "डायलर खोल रहा हूँ।" else "Opening dialer", language)
                }
                SystemIntent.OPEN_DIALER -> {
                    val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                    speak(ttsEngine, if (language == "hi") "डायलर खोल रहा हूँ।" else "Opening dialer", language)
                }
                SystemIntent.CHECK_BATTERY -> {
                    val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (e: Exception) {
                        val i2 = Intent(Settings.ACTION_SETTINGS)
                        i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i2)
                    }
                    speak(ttsEngine, "Opening battery settings", language)
                }
                SystemIntent.FLASHLIGHT -> {
                    val enabled = flashlightEnabled(match) ?: run {
                        Timber.w("Flashlight action rejected: explicit on/off state missing")
                        return
                    }
                    try {
                        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val camId = cm.cameraIdList.firstOrNull() ?: return
                        cm.setTorchMode(camId, enabled)
                        speak(ttsEngine, if (enabled) "Flashlight on" else "Flashlight off", language)
                    } catch (e: Exception) { Timber.e(e, "Flashlight toggle failed") }
                }
                SystemIntent.ALARM -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    speak(ttsEngine, "Opening alarm", language)
                }
                SystemIntent.TIMER -> {
                    val seconds = match.extractedParam?.toIntOrNull()
                    context.startActivity(buildTimerIntent(seconds))
                    speak(ttsEngine, if (language == "hi") "टाइमर की पुष्टि के लिए खोल रहा हूँ।" else "Opening timer for confirmation", language)
                }
                SystemIntent.CALCULATOR -> {
                    val intent = Intent().apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_APP_CALCULATOR)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (e: Exception) { Timber.w(e, "Calc intent failed") }
                    speak(ttsEngine, "Opening calculator", language)
                }
                SystemIntent.OPEN_APP -> {
                    val appName = match.extractedParam ?: return
                    val pm = context.packageManager
                    val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    for (app in packages) {
                        val name = pm.getApplicationLabel(app).toString().lowercase()
                        if (name.contains(appName.lowercase())) {
                            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                speak(ttsEngine, if (language == "hi") "$appName खोल रहा हूँ।" else "Opening $appName", language)
                                return
                            }
                        }
                    }
                    Timber.w("OPEN_APP: could not find '$appName'")
                }
            }
        } catch (e: Exception) { Timber.e(e, "Error executing system intent ${match.intent}") }
    }

    internal fun buildTimerIntent(durationSeconds: Int?): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            durationSeconds?.takeIf { it in 1..86_400 }?.let {
                putExtra(AlarmClock.EXTRA_LENGTH, it)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    internal fun flashlightEnabled(match: SystemIntentMatch): Boolean? =
        when (match.extractedParam) {
            "on" -> true
            "off" -> false
            else -> null
        }

    private fun matchesAny(utterance: String, keywords: List<String>): Boolean =
        keywords.any { utterance.contains(it) }

    private fun extractPhoneNumber(utterance: String): String? {
        Regex("""\b\d{10}\b""").find(utterance)?.let { return it.value }
        Regex("""\+91\s*(\d{10})""").find(utterance)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractFlashlightState(utterance: String): String? {
        if (!matchesAny(utterance, listOf("torch", "flashlight", "टॉर्च", "फ्लैशलाइट", "फ़्लैशलाइट"))) return null

        val off = Regex("""\b(off|band|disable)\b""").containsMatchIn(utterance) ||
            utterance.contains("बंद") || utterance.contains("बुझा")
        val on = Regex("""\b(on|chalu|enable)\b""").containsMatchIn(utterance) ||
            utterance.contains("चालू") || utterance.contains("जलाओ")

        return when {
            off && !on -> "off"
            on && !off -> "on"
            else -> null
        }
    }

    private fun extractDurationSeconds(utterance: String): String? {
        val english = Regex("""\b(\d{1,4})\s*(seconds?|secs?|sec|minutes?|mins?|min|hours?|hrs?|hr)\b""")
            .find(utterance)
        val hindi = Regex("""(\d{1,4})\s*(सेकंडों?|मिनटों?|घंटे?|घंटा)""").find(utterance)
        val match = english ?: hindi ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        val multiplier = when {
            unit.startsWith("hour") || unit.startsWith("hr") || unit.contains("घंट") -> 3_600
            unit.startsWith("min") || unit.contains("मिनट") -> 60
            else -> 1
        }
        val seconds = amount.toLong() * multiplier
        return seconds.takeIf { it in 1L..86_400L }?.toString()
    }

    private fun extractAppName(utterance: String): String? {
        val words = utterance.split(" ")
        val idx = words.indexOfFirst { it == "open" || it == "kholo" || it == "launch" || it == "chalu" || it == "start" }
        return when {
            idx > 0 -> words[idx - 1].trim().takeIf { it.length >= 3 }
            idx == 0 && words.size > 1 -> words[1].trim().takeIf { it.length >= 3 }
            else -> null
        }
    }

    private fun speak(ttsEngine: TTSEngine, text: String, language: String) {
        CoroutineScope(Dispatchers.IO).launch { ttsEngine.speak(text, language) }
    }
}
