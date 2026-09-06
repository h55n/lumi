package ai.lumi.engine

import javax.inject.Inject
import javax.inject.Singleton

enum class TaskType(val id: String) {
    UPI_PAYMENT("upi_payment"),
    WHATSAPP_MESSAGE("whatsapp_message"),
    SETTINGS_WIFI("settings_wifi"),
    SETTINGS_DISPLAY("settings_display"),
    SETTINGS_MAIN("settings_main"),
    SETTINGS_BLUETOOTH("settings_bluetooth"),
    PHONE_CALL("phone_call"),
    CAMERA("camera"),
    IRCTC_BOOKING("irctc_booking"),
    AADHAAR("aadhaar"),
    OPEN_NAMED_APP("open_named_app"),
    UNKNOWN("unknown");
}

/**
 * Keyword-based task classifier.
 * Runs in <5ms — no model needed.
 * Used by TaskEngine to decide whether to try bundle maps before VLM.
 */
@Singleton
class TaskClassifier @Inject constructor(
    private val appInventory: AppInventory
) {

    data class Classification(val type: TaskType, val confidence: Float)

    // BCP-47 locale → keyword map → TaskType
    private val patterns: Map<TaskType, List<String>> = mapOf(
        TaskType.UPI_PAYMENT to listOf(
            // English
            "pay", "send money", "upi", "phonepe", "gpay", "google pay", "paytm", "transfer",
            // Hindi
            "paise", "rupaye", "bhejo", "payment", "bhejna", "paisa"
        ),
        TaskType.WHATSAPP_MESSAGE to listOf(
            "whatsapp", "message", "msg", "chat",
            "sandesh", "whatsapp par", "message karo"
        ),
        TaskType.SETTINGS_WIFI to listOf(
            // English
            "wifi", "wi-fi", "internet", "hotspot", "turn on wifi", "open wifi",
            // Hindi
            "wifi lagao", "internet chalao", "wifi chalu karo", "wifi on karo", "wifi kholo", "wi-fi kholo"
        ),
        TaskType.SETTINGS_DISPLAY to listOf(
            // English
            "display", "dark mode", "brightness", "dark theme", "night mode", "screen brightness",
            // Hindi
            "dark mode lagao", "dark mode on karo", "brightness badhao", "display kholo", "dark mode kholo"
        ),
        TaskType.SETTINGS_BLUETOOTH to listOf(
            "bluetooth", "bt",
            "bluetooth lagao", "bluetooth on karo", "bluetooth chalu karo"
        ),
        TaskType.SETTINGS_MAIN to listOf(
            "settings", "phone settings", "system settings",
            "settings kholo"
        ),
        TaskType.PHONE_CALL to listOf(
            "call", "dial", "ring",
            "call karo", "phone karo", "baat karo"
        ),
        TaskType.CAMERA to listOf(
            "photo", "camera", "picture", "selfie",
            "photo lo", "camera kholo"
        ),
        TaskType.IRCTC_BOOKING to listOf(
            "irctc", "train", "ticket",
            "train ticket", "rail"
        ),
        TaskType.AADHAAR to listOf(
            "aadhaar", "aadhar", "uid",
            "aadhaar card"
        )
    )

    /**
     * Classify [text] (user's spoken goal) into a [TaskType].
     * Returns [TaskType.UNKNOWN] if no pattern matches.
     */
    fun classify(text: String): TaskType {
        return classifyWithConfidence(text).type
    }

    fun classifyWithConfidence(text: String): Classification {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return Classification(TaskType.UNKNOWN, 0f)

        val scores = mutableMapOf<TaskType, Float>()
        for ((type, keywords) in patterns) {
            val matchedKeywords = keywords.filter { lower.contains(it) }
            // Deduplicate substrings so "setting" doesn't count if "settings" matched
            val distinctMatches = matchedKeywords.filter { kw ->
                matchedKeywords.none { other -> other.length > kw.length && other.contains(kw) }
            }
            if (distinctMatches.isNotEmpty()) {
                val score = (0.45f + distinctMatches.size * 0.25f).coerceAtMost(1f)
                scores[type] = score
            }
        }

        // Prioritize specific settings categories over generic SETTINGS_MAIN
        val specificSettings = listOf(
            TaskType.SETTINGS_WIFI,
            TaskType.SETTINGS_DISPLAY,
            TaskType.SETTINGS_BLUETOOTH
        )
        val bestSpecificSetting = specificSettings
            .mapNotNull { type -> scores[type]?.let { type to it } }
            .maxByOrNull { it.second }

        val best = if (bestSpecificSetting != null && bestSpecificSetting.second >= 0.7f) {
            bestSpecificSetting
        } else {
            scores.maxByOrNull { it.value }?.toPair()
        }

        if (best != null && best.second >= 0.7f) {
            return Classification(best.first, best.second)
        }

        // Named installed app ("open YouTube") — treat as open-app guidance target
        if (appInventory.resolvePackage(lower) != null) {
            return Classification(TaskType.OPEN_NAMED_APP, 0.8f)
        }
        return Classification(TaskType.UNKNOWN, best?.second ?: 0f)
    }

    /**
     * Resolve the best installed package for this utterance + task type.
     * Never returns a package that is not installed when alternatives exist.
     */
    fun resolveTargetPackage(text: String, taskType: TaskType): String? {
        // Prefer explicit mention from inventory / aliases
        appInventory.resolvePackage(text)?.let { return it }

        val candidates = packageCandidatesForTask(taskType)
        return appInventory.firstInstalled(candidates)
    }

    /**
     * Returns the package name most likely associated with [taskType].
     * Used to pre-select bundle maps before VLM inference.
     */
    fun packageForTask(taskType: TaskType): String? =
        appInventory.firstInstalled(packageCandidatesForTask(taskType))
            ?: packageCandidatesForTask(taskType).firstOrNull()

    private fun packageCandidatesForTask(taskType: TaskType): List<String> = when (taskType) {
        TaskType.UPI_PAYMENT -> listOf(
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm"
        )
        TaskType.WHATSAPP_MESSAGE -> listOf("com.whatsapp", "com.whatsapp.w4b")
        TaskType.SETTINGS_WIFI, TaskType.SETTINGS_DISPLAY, TaskType.SETTINGS_MAIN, TaskType.SETTINGS_BLUETOOTH ->
            listOf("com.android.settings")
        TaskType.CAMERA -> listOf(
            "com.android.camera2",
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera"
        )
        TaskType.PHONE_CALL -> listOf(
            "com.android.contacts",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.vivo.dialer",
            "com.samsung.android.dialer"
        )
        TaskType.IRCTC_BOOKING -> listOf("cris.org.in.prs.ima", "com.irctc.android")
        TaskType.AADHAAR -> listOf("in.gov.uidai.mAadhaarPlus")
        TaskType.OPEN_NAMED_APP, TaskType.UNKNOWN -> emptyList()
    }
}
