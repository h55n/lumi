package ai.lumi.engine

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class ActionTemplate(
    @Json(name = "package") val packageName: String,
    val intent: String,
    @Json(name = "version_min") val versionMin: Int = 0,
    val steps: List<ActionTemplateStep> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ActionTemplateStep(
    @Json(name = "step_id") val stepId: String,
    val resourceIdHints: List<String> = emptyList(),
    val labelHints: List<String> = emptyList(),
    val expectedClass: String? = null,
    val action: String = "CLICK",
    val tts_hi: String = "",
    val tts_en: String = "",
    val secure: Boolean = false,
    val expectedNextScreenHash: Long? = null
)

/**
 * Action Template Engine (PRD Section 3.2).
 * Resolves bundled deterministic action recipes against live Accessibility trees.
 */
@Singleton
class ActionTemplateEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val elementMatcher: ElementMatcher
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val templateAdapter = moshi.adapter(ActionTemplate::class.java)
    private val templateCache = ConcurrentHashMap<String, ActionTemplate>()

    companion object {
        val BUNDLED_TEMPLATES = listOf(
            "templates/phonepe_send_money.json",
            "templates/whatsapp_send_message.json",
            "templates/whatsapp_settings.json",
            "templates/contacts_make_call.json",
            "templates/settings_wifi.json",
            "templates/netflix_open_account.json",
            "templates/instagram_settings.json",
            "templates/spotify_search.json"
        )
    }

    init {
        loadBundledTemplates()
    }

    private fun loadBundledTemplates() {
        val filesToTry = mutableSetOf<String>()
        try {
            val list = context.assets.list("templates")
            if (!list.isNullOrEmpty()) {
                filesToTry.addAll(list.map { "templates/$it" })
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not list assets/templates")
        }

        // Always include known bundled templates as reliable fallback
        filesToTry.addAll(BUNDLED_TEMPLATES)

        for (assetPath in filesToTry) {
            if (!assetPath.endsWith(".json")) continue
            try {
                val json = try {
                    context.assets.open(assetPath).bufferedReader().readText()
                } catch (e: Exception) {
                    val file = java.io.File("src/main/assets/$assetPath").takeIf { it.exists() }
                        ?: java.io.File("app/src/main/assets/$assetPath").takeIf { it.exists() }
                    file?.readText()
                        ?: javaClass.classLoader?.getResourceAsStream(assetPath)?.bufferedReader()?.readText()
                        ?: javaClass.classLoader?.getResourceAsStream("assets/$assetPath")?.bufferedReader()?.readText()
                }
                if (json != null) {
                    val template = templateAdapter.fromJson(json)
                    if (template != null) {
                        val key = "${template.packageName}:${template.intent}"
                        templateCache[key] = template
                        Timber.d("ActionTemplateEngine: loaded template %s", key)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "ActionTemplateEngine: failed to load %s", assetPath)
            }
        }
    }

    fun getTemplate(packageName: String, intent: String): ActionTemplate? {
        val key = "$packageName:$intent"
        return templateCache[key]
    }

    /**
     * Resolves an [ActionTemplateStep] against the live [root] node.
     * Returns the physical bounding Rect on match, or null if the step cannot be resolved.
     */
    fun resolveStep(step: ActionTemplateStep, root: AccessibilityNodeInfo?): Rect? {
        if (step.secure || root == null) return null

        val parsedIntent = ParsedIntent(
            actionType = step.action,
            entityLabels = step.labelHints,
            entityResourceIds = step.resourceIdHints,
            expectedClass = step.expectedClass
        )

        val match = elementMatcher.findBestMatch(root, parsedIntent)
        return match?.bounds
    }

    /**
     * Converts a template's steps into executable [TaskStep] models for [TaskEngine].
     */
    fun createGuidancePlan(template: ActionTemplate): List<TaskStep> {
        return template.steps.mapIndexed { index, step ->
            TaskStep(
                stepIndex = index,
                targetDescription = step.stepId,
                instructionEn = step.tts_en,
                instructionNative = step.tts_hi,
                relativeX = 0.5f,
                relativeY = 0.5f,
                resolvedBounds = null,
                isLastStep = (index == template.steps.size - 1),
                wait = step.secure,
                nativeLanguage = "hi",
                labelHints = step.labelHints,
                resourceIdHints = step.resourceIdHints,
                action = step.action
            )
        }
    }
}
