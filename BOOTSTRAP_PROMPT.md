# Lumi — Bootstrap Prompt for Claude Code / Cursor / Aider

Paste this prompt when starting or resuming work on Lumi to give the AI agent full context.

---

## Project: Lumi

**"Your phone, explained."**

An on-device, offline-first, multilingual Android overlay assistant that guides first-generation smartphone users through phone tasks step-by-step using a visible cursor and voice instructions in Indian languages.

### Hackathon
- **Event:** iQOO Hackathon 2026, Pune City Battle, September 5–6
- **Team:** html (Hassan Rehman — AI/ML, Mrunmayee Daware — voice/inference, Tanishq Mhetras — Android)
- **Track:** Productivity
- **Demo:** Guide a user through a PhonePe UPI payment (₹500) in Hindi, entirely offline

### Tech stack
- **Language:** Kotlin + Jetpack Compose
- **DI:** Hilt
- **DB:** Room + DataStore
- **Background:** WorkManager
- **Overlay:** WindowManager (TYPE_APPLICATION_OVERLAY for bubble, TYPE_ACCESSIBILITY_OVERLAY for cursor)
- **Screen reading:** MediaProjection
- **Accessibility:** AccessibilityService (LumiAccessibilityService)
- **VLM — Flagship:** Qwen3-VL-4B via Qualcomm GenieX qairt (Hexagon NPU, SM8850)
- **VLM — Budget:** Moondream2 Q4 GGUF via GenieX llama_cpp / llama.cpp+Vulkan
- **ASR:** vasista22/whisper-hindi-medium-Q4 (Hindi, WER 8.2%), Whisper Large v3 Turbo Q4 (English + language detection)
- **TTS:** Kokoro-82M (English, bundled), Android System TTS (Hindi/Indian languages)
- **Memory LLM:** Qwen3-1.7B Q4 (flagship only, post-task extraction)

### Package structure
```
ai.lumi/
├── LumiApplication.kt     # Hilt app class
├── MainActivity.kt         # Entry point
├── MainViewModel.kt
├── onboarding/             # 8-screen self-demonstrating onboarding
├── overlay/                # OverlayService, BubbleView, GuidanceBubbleView
├── cursor/                 # CursorView, CursorAnimator
├── accessibility/          # LumiAccessibilityService, ElementFinder, UITreeCapture
├── screencapture/          # ScreenCaptureService, ScreenshotProcessor
├── engine/                 # TaskEngine (core loop), TaskClassifier, TaskState, TaskStep
├── inference/              # ModelSelector, VLMEngine, MoondreamEngine, TextLLMEngine, DeviceTier
├── voice/                  # VoiceService, WhisperEngine, HindiWhisperEngine, TurboWhisperEngine, TTS
├── uimap/                  # UIMapRepository, BundleMapLoader, UIMapWorker, PerceptualHasher
├── memory/                 # ProfileManager, FormMemoryManager, MemoryRepository, VectorSearchEngine
├── cloud/                  # AnthropicClient (Adaptive Intelligence Mode fallback)
├── settings/               # SettingsActivity, SettingsViewModel
├── data/db/                # LumiDatabase, all DAOs, all entities
├── data/datastore/         # LumiPreferences
├── di/                     # AppModule (Hilt)
└── ui/                     # HomeScreen, LumiTheme, components
```

### What is NOT yet integrated (requires external SDKs)

#### 1. whisper.cpp JNI (CRITICAL PATH)
```bash
# Build whisper.cpp for arm64-v8a
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp
mkdir build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DGGML_VULKAN=ON
make -j8
# Copy libwhisper.so → app/src/main/jniLibs/arm64-v8a/
# Copy JNI Kotlin bindings from whisper.cpp/examples/whisper.android/
# Replace ai.lumi.voice.WhisperJNI with the real binding
```

#### 2. Qualcomm GenieX SDK (Qwen3-VL-4B on NPU)
```
1. Sign up at https://developer.qualcomm.com
2. Download GenieX SDK for Android
3. Place genieX.aar in app/libs/
4. Uncomment the AAR line in app/build.gradle.kts
5. Replace MockQairtRuntime with QualcommGenieXQairtSession
6. Download Qwen3-VL-4B bundle from https://aihub.qualcomm.com
   (compile target: SM8850-AC)
7. Update modelFile path in VLMEngine.kt
```

#### 3. Kokoro-82M TTS
```
1. Download from https://huggingface.co/hexgrad/Kokoro-82M
2. Add ONNX Runtime: implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
3. Place kokoro_82m_en.onnx in app/src/main/assets/models/tts/
4. Replace KokoroTTS.synthesizeMock() with real ONNX inference
```

#### 4. Bundle map screen hashes (CRITICAL FOR DEMO)
```
The pHash values in phonepe_maps.json / whatsapp_maps.json are PLACEHOLDER values.
BEFORE THE HACKATHON:
1. Run the app on the actual iQOO 15 (OriginOS 6)
2. Open PhonePe, navigate to each screen
3. Use ScreenshotProcessor.process() to get 1280×720 frame
4. Use PerceptualHasher.hash() to compute real hash
5. Update phonepe_maps.json with real hash values
6. Repeat for WhatsApp
```

#### 5. OriginOS 6 Settings path for Accessibility
```
Stock Android: Settings → Accessibility → Installed Services → Lumi
OriginOS 6 path MAY differ — test on actual device and update:
- OnboardingActivity.openAccessibilitySettings()
- The cursor's demo navigation in MiniDemoScreen
```

### Build order for hackathon (recommended)

**Day 0 (pre-hackathon):**
1. Compile whisper.cpp for arm64-v8a → verify HindiWhisperEngine transcribes Hindi audio
2. Integrate Kokoro-82M via ONNX Runtime → verify English TTS plays
3. Capture real pHash values for PhonePe + WhatsApp on iQOO 15
4. Test OverlayService + LumiAccessibilityService on iQOO 15 (OriginOS 6)
5. Test TYPE_ACCESSIBILITY_OVERLAY cursor touch-through on Android 16

**Day 1 (hackathon):**
- Hours 1–3: Fix any OriginOS 6 compatibility issues
- Hours 3–5: Wire TaskEngine → VLM → cursor end-to-end
- Hours 5–7: Run 90-second PhonePe demo 10× without failure
- Hours 7–8: Polish onboarding, fix any permission edge cases

**Day 2 (hackathon):**
- Demo rehearsal, pitch refinement, fallback verification

### Qualcomm mentor talking points (Kartikey Rawat)
- "We run Qwen3-VL-4B via Qualcomm AI Hub using the GenieX qairt runtime on the Hexagon NPU, targeting SM8850-AC"
- "Fast-path: Moondream2 Q4 GGUF via GenieX llama_cpp — the GGML Hexagon backend handles it without a pre-compiled bundle"
- "Qwen3-VL-4B chosen specifically because it's pre-optimised on AI Hub for the SD 8 Elite Gen 5 chipset"

### Scoring to maximise
| Dimension | Weight | Strategy |
|---|---|---|
| End product quality | 30% | Working demo, clean UI |
| Novelty & impact | 20% | No offline Android equivalent |
| HackTracker (phone use) | 15% | Every Lumi step uses camera + voice + NPU |
| Technical depth | 15% | Hexagon NPU + whisper.cpp + TYPE_ACCESSIBILITY_OVERLAY |
| Office Kit | 10% | Screen mirror for debug, file transfer for model sideload |
| Demo & pitch | 10% | 90-second PhonePe script |

### Files that need real values before demo
- [ ] `phonepe_maps.json` — replace placeholder screenHash values with real pHash from iQOO 15
- [ ] `whatsapp_maps.json` — same
- [ ] `ModelSpec.*.sha256` — replace "REPLACE_WITH_ACTUAL_SHA256" with real hash after download
- [ ] `WhisperJNI.kt` — replace with real bindings after compiling whisper.cpp
- [ ] `GenieXRuntime.kt` — replace MockQairtRuntime with real GenieX session

---

*Lumi PRD v2.0 — Team html — iQOO Hackathon 2026 Pune — Sept 5–6*
