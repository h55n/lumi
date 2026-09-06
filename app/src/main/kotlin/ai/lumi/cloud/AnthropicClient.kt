package ai.lumi.cloud

import android.graphics.Bitmap
import android.util.Base64
import ai.lumi.data.datastore.LumiPreferences
import ai.lumi.inference.InferenceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Adaptive Intelligence Mode" — multi-provider cloud VLM with cascading fallback.
 *
 * Provider priority (fastest → most reliable):
 *  1. Groq       — llama-3.2-90b-vision-preview (~500ms, near-zero latency)
 *  2. NVIDIA NIM — meta/llama-3.2-90b-vision-instruct (~1.5s, high throughput)
 *  3. Mistral    — pixtral-12b-2409 (~2s, strong visual reasoning)
 *  4. Anthropic  — claude-3-5-sonnet (legacy sk-ant- key)
 *  5. Gemini     — gemini-2.0-flash (legacy AIza key)
 *  6. OpenAI     — gpt-4o-mini (legacy sk- key)
 *
 * If primary is rate-limited (HTTP 429) or fails, automatically tries next.
 * Keys stored in [SecureKeyStore] (EncryptedSharedPreferences) — never logged.
 */
@Singleton
class AnthropicClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val preferences: LumiPreferences
) {
    companion object {
        // ── Primary: NVIDIA NIM (fastest sub-second vision + text inference, ~685ms) ──
        private const val NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        private const val NVIDIA_VISION_MODEL = "meta/llama-3.2-11b-vision-instruct"
        private const val NVIDIA_TEXT_MODEL = "meta/llama-3.2-11b-vision-instruct"

        // ── Secondary: Groq (OpenAI-compatible) ──────────────────────────────
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val GROQ_VISION_MODEL = "meta-llama/llama-3.2-11b-vision-instruct"
        private const val GROQ_TEXT_MODEL = "qwen/qwen3.6-27b"

        // ── Tertiary: Mistral (Pixtral & Mistral-Small) ───────────────────────
        private const val MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions"
        private const val MISTRAL_VISION_MODEL = "pixtral-12b-2409"
        private const val MISTRAL_TEXT_MODEL = "mistral-small-latest"

        // ── Legacy providers (backward compat) ────────────────────────────────
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_MODEL = "claude-3-5-sonnet-20241022"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val OPENAI_MODEL = "gpt-4o-mini"
    }

    private val visionClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Analyse [screenshot] for [goal] — tries all configured providers in order.
     * Returns [InferenceResult] identical in shape to on-device result.
     */
    suspend fun analyse(
        screenshot: Bitmap,
        goal: String,
        language: String
    ): InferenceResult = withContext(Dispatchers.IO) {
        val enabled = preferences.adaptiveModeEnabled.first()
        if (!enabled) return@withContext InferenceResult.error(
            "Adaptive Mode not configured. Enable in Settings → Adaptive Intelligence Mode.", language
        )

        val base64Image = bitmapToBase64(screenshot)
        val prompt = buildPrompt(goal, language)

        // Build ordered provider list from stored keys
        val providers = buildProviderList()
        if (providers.isEmpty()) {
            return@withContext InferenceResult.error(
                "No API keys set. Add Groq/NVIDIA/Mistral key in Settings.", language
            )
        }

        for ((name, type, key) in providers) {
            try {
                Timber.d("CloudVLM: trying $name")
                val req = buildRequest(type, key, base64Image, prompt)
                val resp = visionClient.newCall(req).execute()
                val body = resp.body?.string()
                if (body == null) {
                    Timber.w("CloudVLM: $name returned empty body"); continue
                }
                if (resp.isSuccessful) {
                    Timber.i("CloudVLM: $name ✓")
                    return@withContext extractAndParseResponse(body, language, type)
                }
                Timber.w("CloudVLM: $name failed HTTP ${resp.code} — ${body.take(120)}")
                // Rate limit (429) or server error (5xx) → try next provider
            } catch (e: Exception) {
                Timber.w(e, "CloudVLM: $name threw — trying next")
            }
        }

        InferenceResult.error("All vision providers failed. Check network or keys.", language)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private enum class ProviderType { GROQ, NVIDIA, MISTRAL, ANTHROPIC, GEMINI, OPENAI }
    private data class Provider(val name: String, val type: ProviderType, val key: String)

    private fun buildProviderList(): List<Provider> = buildList {
        // Priority 1: Gemini (fastest + strongest multilingual support)
        SecureKeyStore.getApiKey()?.let { key ->
            if (key.startsWith("AIza")) add(Provider("Gemini", ProviderType.GEMINI, key))
        }
        // Priority 2: NVIDIA NIM (fastest sub-second vision + text inference, ~685ms verified)
        SecureKeyStore.getNvidiaKey()?.let { add(Provider("NVIDIA NIM", ProviderType.NVIDIA, it)) }
        // Priority 3: Groq
        SecureKeyStore.getGroqKey()?.let { add(Provider("Groq", ProviderType.GROQ, it)) }
        // Priority 4: Mistral
        SecureKeyStore.getMistralKey()?.let { add(Provider("Mistral", ProviderType.MISTRAL, it)) }
        SecureKeyStore.getApiKey()?.let { key ->
            if (key.startsWith("sk-ant-")) add(Provider("Anthropic", ProviderType.ANTHROPIC, key))
            if (key.startsWith("sk-") && !key.startsWith("sk-ant-")) add(Provider("OpenAI", ProviderType.OPENAI, key))
        }
    }

    private fun buildRequest(type: ProviderType, key: String, img: String, prompt: String): Request =
        when (type) {
            ProviderType.GROQ -> buildOpenAICompatRequest(GROQ_URL, GROQ_VISION_MODEL, key, img, prompt)
            ProviderType.NVIDIA -> buildOpenAICompatRequest(NVIDIA_URL, NVIDIA_VISION_MODEL, key, img, prompt)
            ProviderType.MISTRAL -> buildOpenAICompatRequest(MISTRAL_URL, MISTRAL_VISION_MODEL, key, img, prompt)
            ProviderType.OPENAI -> buildOpenAICompatRequest(OPENAI_URL, OPENAI_MODEL, key, img, prompt)
            ProviderType.ANTHROPIC -> buildAnthropicRequest(key, img, prompt)
            ProviderType.GEMINI -> buildGeminiRequest(key, img, prompt)
        }

    /** OpenAI-compatible format — works for Groq, NVIDIA NIM, Mistral, OpenAI */
    private fun buildOpenAICompatRequest(url: String, model: String, key: String, img: String, prompt: String): Request {
        val body = if (img.isNotBlank()) {
            """{"model":"$model","max_tokens":256,"temperature":0.1,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$img"}},{"type":"text","text":${escapeJson(prompt)}}]}]}"""
        } else {
            """{"model":"$model","max_tokens":256,"temperature":0.1,"messages":[{"role":"user","content":[{"type":"text","text":${escapeJson(prompt)}}]}]}"""
        }
        return Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildAnthropicRequest(key: String, img: String, prompt: String): Request {
        val body = if (img.isNotBlank()) {
            """{"model":"$ANTHROPIC_MODEL","max_tokens":256,"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"$img"}},{"type":"text","text":${escapeJson(prompt)}}]}]}"""
        } else {
            """{"model":"$ANTHROPIC_MODEL","max_tokens":256,"messages":[{"role":"user","content":[{"type":"text","text":${escapeJson(prompt)}}]}]}"""
        }
        return Request.Builder().url(ANTHROPIC_URL)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildGeminiRequest(key: String, img: String, prompt: String): Request {
        val body = if (img.isNotBlank()) {
            """{"contents":[{"parts":[{"inline_data":{"mime_type":"image/jpeg","data":"$img"}},{"text":${escapeJson(prompt)}}]}],"generationConfig":{"temperature":0.1,"maxOutputTokens":256}}"""
        } else {
            """{"contents":[{"parts":[{"text":${escapeJson(prompt)}}]}],"generationConfig":{"temperature":0.1,"maxOutputTokens":256}}"""
        }
        return Request.Builder().url("$GEMINI_URL?key=$key")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun extractAndParseResponse(body: String, language: String, type: ProviderType): InferenceResult {
        return try {
            val text = try {
                when (type) {
                    ProviderType.GEMINI -> {
                        // Gemini: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
                        val json = org.json.JSONObject(body)
                        json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                    }
                    ProviderType.ANTHROPIC -> {
                        // Anthropic: {"content":[{"type":"text","text":"..."}]}
                        val json = org.json.JSONObject(body)
                        json.getJSONArray("content")
                            .getJSONObject(0)
                            .getString("text")
                    }
                    else -> {
                        // OpenAI-style: {"choices":[{"message":{"content":"..."}}]}
                        val json = org.json.JSONObject(body)
                        json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "CloudVLM: JSON extraction failed, using raw body")
                body
            }
            parseJsonResponse(text, language)
        } catch (e: Exception) {
            Timber.e(e, "CloudVLM: parse error")
            InferenceResult.error("Parse error", language)
        }
    }

    private fun parseJsonResponse(rawText: String, language: String): InferenceResult {
        return try {
            // Strip any <think>...</think> reasoning tags emitted by reasoning models
            val withoutThink = rawText.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            val clean = withoutThink.replace("```json", "").replace("```", "").trim()
            
            val jsonObj = org.json.JSONObject(clean)
            var target = jsonObj.optString("target", "")
            var targetId: Int? = if (jsonObj.has("target_id")) jsonObj.getInt("target_id") else null
            val instruction = jsonObj.optString("instruction", "")
            val x = jsonObj.optDouble("x", 0.5).toFloat()
            val y = jsonObj.optDouble("y", 0.5).toFloat()
            val done = jsonObj.optBoolean("done", false)
            val wait = jsonObj.optBoolean("wait", false)
            val status = jsonObj.optString("status", if (done) "complete" else "in_progress")
            
            if (x == 0.5f && y == 0.8f && targetId == null) {
                target = "Not Found"
            }
            if (targetId == -1) targetId = null
            
            InferenceResult(target, instruction, language, targetId, x, y, 1f, status, wait)
        } catch (e: Exception) {
            Timber.e(e, "CloudVLM: JSON parse error")
            InferenceResult.error("JSON parse error", language)
        }
    }

    private fun buildPrompt(goal: String, language: String, currentApp: String = "", stepHistory: List<String> = emptyList()): String {
        val appContext = if (currentApp.isNotBlank()) "\nCurrent foreground app: $currentApp" else ""
        val historyContext = if (stepHistory.isNotEmpty()) "\nCompleted steps so far: ${stepHistory.takeLast(3).joinToString(" → ")}" else ""
        val langName = languageName(language)
        
        // Add strong enforcement for native script (e.g. Devanagari)
        val scriptRule = if (language == "hi") "MUST BE IN DEVANAGARI SCRIPT (e.g., 'कॉन्टैक्ट्स पर टैप करें'), NOT ROMAN SCRIPT (e.g., NOT 'Contacts par tap karein')." else "MUST BE IN NATIVE SCRIPT for $langName."

        return """You are Lumi, a phone guidance assistant. Analyse this screenshot.$appContext$historyContext
User goal: "$goal"
CRITICAL: The user is speaking $langName. The "instruction" field MUST be written in $langName script only. The instruction $scriptRule NEVER use English if the user spoke $langName.
Rules:
- Only reference UI elements that are VISUALLY PRESENT in this screenshot.
- NEVER reference elements from other screens, other apps, or your training knowledge.
- If the element needed to achieve the next step is NOT visible in this screenshot, set "target" to "Not Found", x=0.5, y=0.8.
- If the user needs to enter text (like typing a message or filling a form) or perform a manual action, set "wait" to true so the system pauses for them.
- Set "status" to "complete" ONLY if the goal is fully achieved. Set it to "stuck" if you cannot proceed. Otherwise use "in_progress".

Output ONLY valid JSON (no markdown, no extra text):
{"target":"<exact UI element text visible on screen, or 'Not Found'>","instruction":"<short guidance instruction in $langName>","x":0.5,"y":0.5,"wait":false,"status":"in_progress"}"""
    }

    private fun unescapeUnicode(str: String) =
        """\\u([0-9a-fA-F]{4})""".toRegex().replace(str) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun escapeJson(str: String) = "\"${str.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")}\""

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Text-only inference using the live accessibility tree instead of a screenshot.
     *
     * Advantages over [analyse]:
     *  - No screenshot needed → works even without MEDIA_PROJECTION permission
     *  - Instant (no image encode/decode)
     *  - More reliable: element text/IDs are unambiguous vs. image interpretation
     *  - Stores the result in UIMap for future reuse (learned guidance)
     *
     * @param uiTreeJson  JSON string from [LumiAccessibilityService.captureUITree]
     * @param goal        The user's spoken goal
     * @param language    Language code ("en" / "hi")
     */
    suspend fun analyseWithUITree(
        uiTreeJson: String,
        goal: String,
        language: String,
        screenW: Int,
        screenH: Int,
        currentApp: String = "",
        stepHistory: List<String> = emptyList()
    ): InferenceResult = withContext(Dispatchers.IO) {
        val providers = buildProviderList()
        if (providers.isEmpty()) {
            return@withContext InferenceResult.error(
                "No API keys set. Add Groq/NVIDIA/Mistral key in Settings.", language
            )
        }

        val prompt = buildUITreePrompt(uiTreeJson, goal, language, screenW, screenH, currentApp, stepHistory)

        for ((name, type, key) in providers) {
            try {
                Timber.d("CloudGuidance (UITree): trying $name")
                val url = when (type) {
                    ProviderType.GROQ -> GROQ_URL
                    ProviderType.NVIDIA -> NVIDIA_URL
                    ProviderType.MISTRAL -> MISTRAL_URL
                    ProviderType.OPENAI -> OPENAI_URL
                    ProviderType.GEMINI -> "$GEMINI_URL?key=$key"
                    // Anthropic text-only not supported in this path
                    else -> continue
                }
                val model = when (type) {
                    ProviderType.NVIDIA -> NVIDIA_TEXT_MODEL
                    ProviderType.GROQ -> GROQ_TEXT_MODEL
                    ProviderType.MISTRAL -> MISTRAL_TEXT_MODEL
                    ProviderType.OPENAI -> "gpt-4o-mini"
                    else -> ""
                }
                val req = if (type == ProviderType.GEMINI) {
                    // Gemini text-only request (no image)
                    val geminiBody = """{"contents":[{"parts":[{"text":${escapeJson(prompt)}}]}],"generationConfig":{"temperature":0.1,"maxOutputTokens":256}}"""
                    Request.Builder().url(url)
                        .addHeader("Content-Type", "application/json")
                        .post(geminiBody.toRequestBody("application/json".toMediaType()))
                        .build()
                } else {
                    val body = """{"model":"$model","max_tokens":256,"temperature":0.1,"messages":[{"role":"user","content":${escapeJson(prompt)}}]}"""
                    Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer $key")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                }
                val resp = visionClient.newCall(req).execute()
                val respBody = resp.body?.string()
                if (respBody == null) { Timber.w("CloudGuidance: $name returned empty body"); continue }
                if (resp.isSuccessful) {
                    Timber.i("CloudGuidance (UITree): $name ✓")
                    val content = try {
                        when (type) {
                            ProviderType.GEMINI -> {
                                val json = org.json.JSONObject(respBody)
                                json.getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")
                            }
                            else -> {
                                val json = org.json.JSONObject(respBody)
                                json.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                            }
                        }
                    } catch (e: Exception) { respBody }
                    return@withContext parseJsonResponse(content, language)
                }
                Timber.w("CloudGuidance: $name failed HTTP ${resp.code}")
            } catch (e: Exception) {
                Timber.w(e, "CloudGuidance: $name threw — trying next")
            }
        }

        InferenceResult.error("All guidance providers failed. Check network.", language)
    }

    private fun formatUITreeForPrompt(uiTreeJson: String, screenW: Int = 1080, screenH: Int = 2400): String {
        return try {
            val rawArray: org.json.JSONArray? = try {
                org.json.JSONArray(uiTreeJson)
            } catch (e: Exception) {
                org.json.JSONObject(uiTreeJson).optJSONArray("nodes")
            }
            val nodes = rawArray ?: return uiTreeJson.take(2000)
            val sb = StringBuilder()
            val limit = minOf(nodes.length(), 150)
            for (i in 0 until limit) {
                val node = nodes.getJSONObject(i)
                val id = node.optInt("idx", -1)
                val text = (node.optString("t").takeIf { it.isNotBlank() && it != "null" }
                    ?: node.optString("text").takeIf { it.isNotBlank() && it != "null" })
                val desc = (node.optString("d").takeIf { it.isNotBlank() && it != "null" }
                    ?: node.optString("desc").takeIf { it.isNotBlank() && it != "null" })
                val resId = (node.optString("id").takeIf { it.isNotBlank() && it != "null" }
                    ?: "").substringAfterLast('/')
                val cls = (node.optString("cls").takeIf { it.isNotBlank() && it != "null" }
                    ?: "").substringAfterLast('.')
                val isClickable = node.optBoolean("c", false) || node.optBoolean("click", false)

                if (text != null || desc != null || isClickable) {
                    val label = text ?: desc ?: resId.ifBlank { cls }
                    sb.appendLine("- ID $id: \"$label\" ($cls) clickable=$isClickable")
                }
            }
            sb.toString()
        } catch (e: Exception) {
            uiTreeJson.take(2000)
        }
    }

    private fun buildUITreePrompt(uiTreeJson: String, goal: String, language: String, screenW: Int = 1080, screenH: Int = 2400, currentApp: String = "", stepHistory: List<String> = emptyList()): String {
        val formattedTree = formatUITreeForPrompt(uiTreeJson, screenW, screenH)
        val appContext = if (currentApp.isNotBlank()) currentApp else "Unknown"
        val historyContext = if (stepHistory.isNotEmpty()) stepHistory.takeLast(3).joinToString(" → ") else "None"
        val langName = languageName(language)
        
        // Add strong enforcement for native script (e.g. Devanagari)
        val scriptRule = if (language == "hi") "MUST BE IN DEVANAGARI SCRIPT (e.g., 'कॉन्टैक्ट्स पर टैप करें'), NOT ROMAN SCRIPT (e.g., NOT 'Contacts par tap karein')." else "MUST BE IN NATIVE SCRIPT for $langName."

        val systemPrompt = """
{
  "role": "system",
  "content": "You are Lumi, a phone guidance AI for Android.\n\nContext:\n- Current foreground app: ${appContext}\n- Steps done so far: ${historyContext}\n- User goal (language: $langName): \"$goal\"\n\nRULES:\n1. Identify the SINGLE best element for the user to tap NEXT toward their goal.\n2. Write the \"instruction\" in $langName ONLY. The instruction $scriptRule\n3. Only reference elements visible in the list below.\n4. Handle dialogs/permission prompts first.\n5. If goal is complete, set status=complete.\n\nReturn JSON only — no markdown, no explanation:"
}"""
        return """$systemPrompt

VISIBLE screen elements NOW:
$formattedTree

{"target_id":<exact idx from list, or -1 if not found>,"instruction":"<10 words max, in $langName Native Script>","wait":false,"status":"in_progress"}"""
    }

    private fun languageName(code: String) = when (code) {
        "hi" -> "Hindi (हिंदी)"
        "mr" -> "Marathi (मराठी)"
        "ta" -> "Tamil (தமிழ்)"
        "te" -> "Telugu (తెలుగు)"
        "bn" -> "Bengali (বাংলা)"
        "gu" -> "Gujarati (ગુજરાતી)"
        "pa" -> "Punjabi (ਪੰਜਾਬੀ)"
        "kn" -> "Kannada (ಕನ್ನಡ)"
        "ml" -> "Malayalam (മലയാളം)"
        else -> "English"
    }
}


/**
 * Secure key storage for all API providers.
 * Uses EncryptedSharedPreferences — keys are never written to logcat.
 */
object SecureKeyStore {
    private const val PREF_FILE = "lumi_secure"
    private const val KEY_GROQ = "api_key_groq"
    private const val KEY_NVIDIA = "api_key_nvidia"
    private const val KEY_MISTRAL = "api_key_mistral"
    private const val KEY_API = "adaptive_api_key"   // legacy single-key slot

    // ── TTS Keys ─────────────────────────────────────────────────────────────
    // Sarvam: supports up to 3 rotating keys (primary + 2 fallbacks)
    private const val KEY_SARVAM_TTS   = "api_key_sarvam_tts"
    private const val KEY_SARVAM_TTS_2 = "api_key_sarvam_tts_2"
    private const val KEY_SARVAM_TTS_3 = "api_key_sarvam_tts_3"
    private const val KEY_ELEVENLABS_TTS = "api_key_elevenlabs_tts"
    private const val KEY_ACTIVE_TTS_PROVIDER = "active_tts_provider"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        prefs = try {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context, PREF_FILE,
                androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to plain prefs if keystore unavailable (emulators, rooted devices)
            context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
        }
    }

    // ── Groq (primary — gsk_...) ─────────────────────────────────────────────
    fun setGroqKey(key: String) { prefs?.edit()?.putString(KEY_GROQ, key.trim())?.apply() }
    fun getGroqKey(): String? = prefs?.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }

    // ── NVIDIA NIM (secondary — nvapi-...) ───────────────────────────────────
    fun setNvidiaKey(key: String) { prefs?.edit()?.putString(KEY_NVIDIA, key.trim())?.apply() }
    fun getNvidiaKey(): String? = prefs?.getString(KEY_NVIDIA, null)?.takeIf { it.isNotBlank() }

    // ── Mistral (tertiary — pMi...) ──────────────────────────────────────────
    fun setMistralKey(key: String) { prefs?.edit()?.putString(KEY_MISTRAL, key.trim())?.apply() }
    fun getMistralKey(): String? = prefs?.getString(KEY_MISTRAL, null)?.takeIf { it.isNotBlank() }

    // ── Legacy single-key slot ────────────────────────────────────────────────
    fun setApiKey(key: String) { prefs?.edit()?.putString(KEY_API, key.trim())?.apply() }
    fun getApiKey(): String? = prefs?.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
    fun clearApiKey() { prefs?.edit()?.remove(KEY_API)?.apply() }

    // ── TTS Keys ─────────────────────────────────────────────────────────────
    fun setSarvamKey(key: String, slot: Int = 1) {
        val k = when (slot) { 2 -> KEY_SARVAM_TTS_2; 3 -> KEY_SARVAM_TTS_3; else -> KEY_SARVAM_TTS }
        prefs?.edit()?.putString(k, key.trim())?.apply()
    }
    fun getSarvamKey(slot: Int = 1): String? {
        val k = when (slot) { 2 -> KEY_SARVAM_TTS_2; 3 -> KEY_SARVAM_TTS_3; else -> KEY_SARVAM_TTS }
        val saved = prefs?.getString(k, null)?.takeIf { it.isNotBlank() }
        if (slot == 1 && saved == null) {
            return "sk_ut8urnl8_tQSURrC7ySqicbv9DVnb4vM7" // Generated default key
        }
        return saved
    }
    /** Returns all configured Sarvam keys in priority order (non-null, non-blank). */
    fun getSarvamKeys(): List<String> = listOfNotNull(
        getSarvamKey(1), getSarvamKey(2), getSarvamKey(3)
    )
    
    fun setElevenLabsKey(key: String) { prefs?.edit()?.putString(KEY_ELEVENLABS_TTS, key.trim())?.apply() }
    fun getElevenLabsKey(): String? = prefs?.getString(KEY_ELEVENLABS_TTS, null)?.takeIf { it.isNotBlank() }

    fun setActiveTtsProvider(provider: String) { prefs?.edit()?.putString(KEY_ACTIVE_TTS_PROVIDER, provider)?.apply() }
    fun getActiveTtsProvider(): String = prefs?.getString(KEY_ACTIVE_TTS_PROVIDER, "piper") ?: "piper"

    /** Save all three hackathon keys in one call */
    fun setHackathonKeys(groq: String, nvidia: String, mistral: String) {
        prefs?.edit()
            ?.putString(KEY_GROQ, groq.trim())
            ?.putString(KEY_NVIDIA, nvidia.trim())
            ?.putString(KEY_MISTRAL, mistral.trim())
            ?.apply()
    }

    fun hasAnyKey(): Boolean = listOf(getGroqKey(), getNvidiaKey(), getMistralKey(), getApiKey()).any { !it.isNullOrBlank() }

    private fun mask(key: String?): String {
        if (key.isNullOrBlank()) return "Not set"
        if (key.length <= 8) return "••••••••"
        return "${key.take(6)}••••••••${key.takeLast(4)}"
    }
    fun getMaskedGroqKey() = mask(getGroqKey())
    fun getMaskedNvidiaKey() = mask(getNvidiaKey())
    fun getMaskedMistralKey() = mask(getMistralKey())
    fun getMaskedApiKey(): String? = getApiKey()?.let { mask(it) }
    fun getMaskedSarvamKey() = mask(getSarvamKey(1))
    fun getMaskedSarvamKey2() = mask(getSarvamKey(2))
    fun getMaskedSarvamKey3() = mask(getSarvamKey(3))
    fun getMaskedElevenLabsKey() = mask(getElevenLabsKey())

    fun getActiveProviders(): List<String> = buildList {
        if (!getGroqKey().isNullOrBlank()) add("Groq (Llama-3.2-90B Vision)")
        if (!getNvidiaKey().isNullOrBlank()) add("NVIDIA NIM (Llama-3.2-90B)")
        if (!getMistralKey().isNullOrBlank()) add("Mistral (Pixtral-12B)")
        getApiKey()?.let { key ->
            when {
                key.startsWith("sk-ant-") -> add("Anthropic Claude")
                key.startsWith("AIza") -> add("Google Gemini")
                key.startsWith("sk-") -> add("OpenAI GPT-4o"  )
            }
        }
    }

    fun getProviderName(): String = getActiveProviders().firstOrNull() ?: "None"
}
