package ai.lumi.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ai.lumi.cloud.SecureKeyStore
import ai.lumi.data.datastore.LumiPreferences
import ai.lumi.data.db.dao.UserProfileDao
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.UserProfileEntity
import ai.lumi.memory.MemoryRepository
import ai.lumi.ui.components.LumiButton
import ai.lumi.ui.components.LumiCard
import ai.lumi.ui.components.SUPPORTED_LANGUAGES
import ai.lumi.ui.theme.*
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumiTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: LumiPreferences,
    private val userProfileDao: UserProfileDao,
    private val memoryRepository: MemoryRepository,
    private val modelDownloadManager: ai.lumi.inference.ModelDownloadManager,
    private val modelSelector: ai.lumi.inference.ModelSelector,
    private val deviceScannerService: ai.lumi.engine.DeviceScannerService
) : ViewModel() {

    fun startDeepScan() {
        deviceScannerService.startDeepScan()
    }

    val preferredLanguage = preferences.preferredLanguage
    val bubbleSizeDp = preferences.bubbleSizeDp
    val adaptiveModeEnabled = preferences.adaptiveModeEnabled
    val autoTapEnabled = preferences.autoTapEnabled
    val profileEntries: StateFlow<List<UserProfileEntity>> = userProfileDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Active providers (refreshed on compose recomposition via StateFlow)
    val activeProviders = MutableStateFlow(SecureKeyStore.getActiveProviders())
    val maskedGroqKey = MutableStateFlow(SecureKeyStore.getMaskedGroqKey())
    val maskedNvidiaKey = MutableStateFlow(SecureKeyStore.getMaskedNvidiaKey())
    val maskedMistralKey = MutableStateFlow(SecureKeyStore.getMaskedMistralKey())
    // Legacy single-key slot
    val currentApiKeyMasked = MutableStateFlow(SecureKeyStore.getMaskedApiKey())
    val activeProvider = MutableStateFlow(SecureKeyStore.getProviderName())
    
    // TTS
    val activeTtsProvider = MutableStateFlow(SecureKeyStore.getActiveTtsProvider())
    val maskedSarvamKey = MutableStateFlow(SecureKeyStore.getMaskedSarvamKey())
    val maskedSarvamKey2 = MutableStateFlow(SecureKeyStore.getMaskedSarvamKey2())
    val maskedSarvamKey3 = MutableStateFlow(SecureKeyStore.getMaskedSarvamKey3())
    val maskedElevenLabsKey = MutableStateFlow(SecureKeyStore.getMaskedElevenLabsKey())

    val downloadableModels = ai.lumi.inference.ModelSpec.entries.filter {
        it.isDirectDownloadable && it.hasTrustedSha256 && it.hasRuntimeIntegration
    }
    
    private val _downloadStates = MutableStateFlow<Map<ai.lumi.inference.ModelSpec, ai.lumi.inference.DownloadState>>(
        downloadableModels.associateWith { ai.lumi.inference.DownloadState.Idle }
    )
    val downloadStates = _downloadStates.asStateFlow()

    init {
        viewModelScope.launch {
            downloadableModels.forEach { spec ->
                if (modelDownloadManager.isDownloaded(spec)) {
                    _downloadStates.update {
                        it + (spec to ai.lumi.inference.DownloadState.Complete(
                            spec,
                            modelDownloadManager.modelFile(spec)
                        ))
                    }
                }
            }
        }
    }

    fun downloadModel(spec: ai.lumi.inference.ModelSpec) {
        viewModelScope.launch {
            modelDownloadManager.download(spec).collect { state ->
                _downloadStates.update { it + (spec to state) }
            }
        }
    }

    fun setLanguage(lang: String) = viewModelScope.launch { preferences.setPreferredLanguage(lang) }
    fun setBubbleSize(dp: Int) = viewModelScope.launch { preferences.setBubbleSizeDp(dp) }
    fun setAdaptiveMode(enabled: Boolean) = viewModelScope.launch { preferences.setAdaptiveModeEnabled(enabled) }
    fun setAutoTap(enabled: Boolean) = viewModelScope.launch { preferences.setAutoTapEnabled(enabled) }

    private fun refreshProviderState() {
        activeProviders.value = SecureKeyStore.getActiveProviders()
        maskedGroqKey.value = SecureKeyStore.getMaskedGroqKey()
        maskedNvidiaKey.value = SecureKeyStore.getMaskedNvidiaKey()
        maskedMistralKey.value = SecureKeyStore.getMaskedMistralKey()
        currentApiKeyMasked.value = SecureKeyStore.getMaskedApiKey()
        activeProvider.value = SecureKeyStore.getProviderName()
        activeTtsProvider.value = SecureKeyStore.getActiveTtsProvider()
        maskedSarvamKey.value = SecureKeyStore.getMaskedSarvamKey()
        maskedSarvamKey2.value = SecureKeyStore.getMaskedSarvamKey2()
        maskedSarvamKey3.value = SecureKeyStore.getMaskedSarvamKey3()
        maskedElevenLabsKey.value = SecureKeyStore.getMaskedElevenLabsKey()
    }

    fun saveGroqKey(key: String) {
        SecureKeyStore.setGroqKey(key)
        refreshProviderState()
        viewModelScope.launch { preferences.setAdaptiveModeEnabled(SecureKeyStore.hasAnyKey()) }
    }

    fun saveNvidiaKey(key: String) {
        SecureKeyStore.setNvidiaKey(key)
        refreshProviderState()
        viewModelScope.launch { preferences.setAdaptiveModeEnabled(SecureKeyStore.hasAnyKey()) }
    }

    fun saveMistralKey(key: String) {
        SecureKeyStore.setMistralKey(key)
        refreshProviderState()
        viewModelScope.launch { preferences.setAdaptiveModeEnabled(SecureKeyStore.hasAnyKey()) }
    }

    fun saveApiKey(key: String) {
        SecureKeyStore.setApiKey(key)
        refreshProviderState()
        viewModelScope.launch { preferences.setAdaptiveModeEnabled(SecureKeyStore.hasAnyKey()) }
    }
    
    fun setTtsProvider(provider: String) {
        SecureKeyStore.setActiveTtsProvider(provider)
        refreshProviderState()
    }
    
    fun saveSarvamKey(key: String, slot: Int = 1) {
        SecureKeyStore.setSarvamKey(key, slot)
        refreshProviderState()
    }
    
    fun saveElevenLabsKey(key: String) {
        SecureKeyStore.setElevenLabsKey(key)
        refreshProviderState()
    }

    fun clearApiKey() {
        SecureKeyStore.clearApiKey()
        refreshProviderState()
        viewModelScope.launch { preferences.setAdaptiveModeEnabled(SecureKeyStore.hasAnyKey()) }
    }

    fun clearMemory() = viewModelScope.launch {
        memoryRepository.clearAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val language by viewModel.preferredLanguage.collectAsStateWithLifecycle(initialValue = "en")
    val bubbleSize by viewModel.bubbleSizeDp.collectAsStateWithLifecycle(initialValue = 56)
    val adaptiveMode by viewModel.adaptiveModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val autoTap by viewModel.autoTapEnabled.collectAsStateWithLifecycle(initialValue = false)
    val profile by viewModel.profileEntries.collectAsStateWithLifecycle()
    val currentMaskedKey by viewModel.currentApiKeyMasked.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()
    
    val activeTts by viewModel.activeTtsProvider.collectAsStateWithLifecycle()
    val maskedSarvam by viewModel.maskedSarvamKey.collectAsStateWithLifecycle()
    val maskedSarvam2 by viewModel.maskedSarvamKey2.collectAsStateWithLifecycle()
    val maskedSarvam3 by viewModel.maskedSarvamKey3.collectAsStateWithLifecycle()
    val maskedElevenLabs by viewModel.maskedElevenLabsKey.collectAsStateWithLifecycle()
    
    var showClearDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var sarvamInput by remember { mutableStateOf("") }
    var sarvamInput2 by remember { mutableStateOf("") }
    var sarvamInput3 by remember { mutableStateOf("") }
    var elevenLabsInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = LumiInk) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LumiInk)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LumiDark)
            )
        },
        containerColor = LumiDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Language ──────────────────────────────────────────────────────
            SettingsSection("Language") {
                SUPPORTED_LANGUAGES.forEach { lang ->
                    SettingsRadioRow(
                        label = "${lang.nativeLabel} — ${lang.label}",
                        selected = language == lang.code,
                        onClick = { viewModel.setLanguage(lang.code) }
                    )
                }
            }

            // ── Bubble Size ───────────────────────────────────────────────────
            SettingsSection("Bubble Size") {
                listOf(40 to "Small", 56 to "Medium (default)", 72 to "Large").forEach { (dp, label) ->
                    SettingsRadioRow(label = label, selected = bubbleSize == dp, onClick = { viewModel.setBubbleSize(dp) })
                }
            }
            
            // ── Text-to-Speech (TTS) Provider ─────────────────────────────────
            SettingsSection("Text-to-Speech (TTS)") {
                Text(
                    "Choose the voice engine Lumi uses to speak out loud.",
                    fontSize = 14.sp, color = LumiMuted
                )
                Spacer(Modifier.height(8.dp))

                listOf("piper" to "Piper TTS (On-Device Neural Voice, Free & Offline)",
                       "sarvam" to "Sarvam AI (Best for Indian)", 
                       "elevenlabs" to "ElevenLabs (High Quality)",
                       "system" to "System TTS (Google/Device Default)").forEach { (id, label) ->
                    SettingsRadioRow(label = label, selected = activeTts == id, onClick = { viewModel.setTtsProvider(id) })
                }
                
                Spacer(Modifier.height(8.dp))

                // Sarvam Key 1 (primary)
                Text("Sarvam AI Key 1 (Primary)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedSarvam ?: "Not set", fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = sarvamInput, label = "Sarvam key 1", onValueChange = { sarvamInput = it }) {
                    if (sarvamInput.isNotBlank()) { viewModel.saveSarvamKey(sarvamInput.trim(), 1); sarvamInput = "" }
                }

                // Sarvam Key 2 (fallback)
                Text("Sarvam AI Key 2 (Fallback)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedSarvam2 ?: "Not set", fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = sarvamInput2, label = "Sarvam key 2", onValueChange = { sarvamInput2 = it }) {
                    if (sarvamInput2.isNotBlank()) { viewModel.saveSarvamKey(sarvamInput2.trim(), 2); sarvamInput2 = "" }
                }

                // Sarvam Key 3 (fallback)
                Text("Sarvam AI Key 3 (Fallback)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedSarvam3 ?: "Not set", fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = sarvamInput3, label = "Sarvam key 3", onValueChange = { sarvamInput3 = it }) {
                    if (sarvamInput3.isNotBlank()) { viewModel.saveSarvamKey(sarvamInput3.trim(), 3); sarvamInput3 = "" }
                }

                // ElevenLabs Key
                Text("ElevenLabs Key", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedElevenLabs, fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = elevenLabsInput, label = "ElevenLabs key", onValueChange = { elevenLabsInput = it }) {
                    if (elevenLabsInput.isNotBlank()) { viewModel.saveElevenLabsKey(elevenLabsInput.trim()); elevenLabsInput = "" }
                }
            }

            // ── Adaptive Intelligence Mode ─────────────────────────────────────
            SettingsSection("Adaptive Intelligence Mode") {
                val activeProviders by viewModel.activeProviders.collectAsStateWithLifecycle()
                val maskedGroq by viewModel.maskedGroqKey.collectAsStateWithLifecycle()
                val maskedNvidia by viewModel.maskedNvidiaKey.collectAsStateWithLifecycle()
                val maskedMistral by viewModel.maskedMistralKey.collectAsStateWithLifecycle()
                var groqInput by remember { mutableStateOf("") }
                var nvidiaInput by remember { mutableStateOf("") }
                var mistralInput by remember { mutableStateOf("") }

                Text(
                    "Vision AI providers (cascade: Groq → NVIDIA → Mistral). Keys encrypted on-device.",
                    fontSize = 14.sp, color = LumiMuted
                )
                Spacer(Modifier.height(8.dp))

                // Active provider status chips
                if (activeProviders.isNotEmpty()) {
                    Text("Active providers:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = LumiInk)
                    activeProviders.forEachIndexed { i, p ->
                        Text(
                            "${i + 1}. $p ✓",
                            fontSize = 13.sp,
                            color = LumiSaffron,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Groq key
                Text("Groq Key (gsk_...)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedGroq, fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = groqInput, label = "Groq key", onValueChange = { groqInput = it }) {
                    if (groqInput.isNotBlank()) { viewModel.saveGroqKey(groqInput.trim()); groqInput = "" }
                }

                // NVIDIA key
                Text("NVIDIA NIM Key (nvapi-...)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedNvidia, fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = nvidiaInput, label = "NVIDIA key", onValueChange = { nvidiaInput = it }) {
                    if (nvidiaInput.isNotBlank()) { viewModel.saveNvidiaKey(nvidiaInput.trim()); nvidiaInput = "" }
                }

                // Mistral key
                Text("Mistral Key (pMi...)", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = LumiInk)
                Text(maskedMistral, fontSize = 12.sp, color = LumiMuted)
                KeyInputRow(value = mistralInput, label = "Mistral key", onValueChange = { mistralInput = it }) {
                    if (mistralInput.isNotBlank()) { viewModel.saveMistralKey(mistralInput.trim()); mistralInput = "" }
                }

                // Legacy single-key field (Anthropic/Gemini/OpenAI)
                if (currentMaskedKey != null) {
                    Text("Legacy key: $activeProvider — $currentMaskedKey", fontSize = 12.sp, color = LumiMuted)
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = apiKeyInput, onValueChange = { apiKeyInput = it },
                    label = { Text("Legacy API key (Anthropic / Gemini / OpenAI)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LumiSaffron, focusedLabelColor = LumiSaffron)
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LumiButton(if (currentMaskedKey != null) "Update Key" else "Save Key", onClick = {
                        if (apiKeyInput.isNotBlank()) {
                            viewModel.saveApiKey(apiKeyInput.trim())
                            apiKeyInput = ""
                        }
                    }, modifier = Modifier.weight(1f))
                    if (currentMaskedKey != null) {
                        OutlinedButton(onClick = { viewModel.clearApiKey() }) {
                            Text("Clear", color = Color(0xFFDC2626))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", fontSize = 15.sp, color = LumiInk)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = adaptiveMode,
                        onCheckedChange = viewModel::setAdaptiveMode,
                        colors = SwitchDefaults.colors(checkedThumbColor = LumiSaffron, checkedTrackColor = LumiSaffron.copy(alpha = 0.3f))
                    )
                }
            }

            // ── Auto-Tap ─────────────────────────────────────────────────────
            SettingsSection("Auto-Tap") {
                Text(
                    "When enabled, Lumi will automatically tap the highlighted element for you. Disabled for banking, payment, and high-risk apps.",
                    fontSize = 13.sp, color = LumiMuted
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Auto-Tap", fontSize = 15.sp, color = LumiInk, fontWeight = FontWeight.Medium)
                        Text("Excludes banking & sensitive apps", fontSize = 12.sp, color = LumiMuted)
                    }
                    Switch(
                        checked = autoTap,
                        onCheckedChange = viewModel::setAutoTap,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LumiInk,
                            checkedTrackColor = LumiSaffron,
                            uncheckedThumbColor = LumiMuted,
                            uncheckedTrackColor = LumiSurfaceElevated,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            // ── Offline Models ────────────────────────────────────────────────
            SettingsSection("Offline Models") {
                val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
                
                Text(
                    "Download local AI models to use Lumi without internet access. Models are large (~100MB to ~2GB).",
                    fontSize = 13.sp, color = LumiMuted
                )
                Spacer(Modifier.height(12.dp))
                
                viewModel.downloadableModels.forEach { spec ->
                    val state = downloadStates[spec] ?: ai.lumi.inference.DownloadState.Idle
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.displayName, fontSize = 15.sp, color = LumiInk, fontWeight = FontWeight.Medium)
                            
                            val statusText = when (state) {
                                is ai.lumi.inference.DownloadState.Idle -> "Not downloaded"
                                is ai.lumi.inference.DownloadState.Downloading -> "Downloading: ${state.progressPercent}%"
                                is ai.lumi.inference.DownloadState.Verifying -> "Verifying..."
                                is ai.lumi.inference.DownloadState.Complete -> "Ready"
                                is ai.lumi.inference.DownloadState.Failed -> "Failed: ${state.reason}"
                                is ai.lumi.inference.DownloadState.WifiRequired -> "Waiting for Wi-Fi"
                                is ai.lumi.inference.DownloadState.CloudMode -> "Cloud Mode"
                            }
                            Text(statusText, fontSize = 12.sp, color = if (state is ai.lumi.inference.DownloadState.Failed) Color(0xFFDC2626) else LumiMuted)
                        }
                        
                        when (state) {
                            is ai.lumi.inference.DownloadState.Downloading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = LumiSaffron,
                                    strokeWidth = 2.dp,
                                    progress = { state.progressPercent / 100f }
                                )
                            }
                            is ai.lumi.inference.DownloadState.Complete -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = LumiSaffron,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = { viewModel.downloadModel(spec) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LumiSaffron),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, LumiSaffron),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Download", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── Device Scanner ────────────────────────────────────────────────
            SettingsSection("Learn My Phone") {
                Text(
                    "Lumi will automatically open all your installed apps one by one to map and remember their layouts. This takes a few minutes.",
                    fontSize = 13.sp, color = LumiMuted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.startDeepScan() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LumiSaffron),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, LumiSaffron)
                ) {
                    Text("Start Deep Scan")
                }
            }

            // ── Memory ────────────────────────────────────────────────────────
            SettingsSection("What Lumi Remembers") {
                if (profile.isEmpty()) {
                    Text("Nothing yet — Lumi will learn as you use it.", fontSize = 14.sp, color = LumiMuted)
                } else {
                    profile.forEach { entry ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${entry.key}:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LumiInk, modifier = Modifier.width(120.dp))
                            Text(entry.value, fontSize = 14.sp, color = Color(0xFFCCCCDD))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFDC2626))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear all memories")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear memories?") },
            text = {
                Text("This deletes saved profile facts, form-memory values, task history, and memory vectors. Downloaded models and provider API keys are not deleted. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMemory(); showClearDialog = false }) {
                    Text("Clear", color = Color(0xFFDC2626))
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    LumiCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LumiSaffron, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingsRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = LumiSaffron))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, color = LumiInk)
    }
}

/**
 * Compact key-entry row: text field + small Save button side by side.
 * Uses a fixed 44dp button height instead of the full 56dp LumiButton
 * so key sections stay tight and don't create large empty gaps.
 */
@Composable
private fun KeyInputRow(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LumiSaffron,
                focusedLabelColor = LumiSaffron,
                unfocusedBorderColor = LumiMuted.copy(alpha = 0.4f)
            )
        )
        Button(
            onClick = onSave,
            modifier = Modifier.height(44.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LumiSaffron,
                contentColor = LumiInk
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
        ) {
            Text("Save", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
