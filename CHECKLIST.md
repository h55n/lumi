# Lumi — Complete Pre-Demo Checklist
### iQOO Hackathon 2026 · Pune · September 5–6
### Every box must be ✅ before stepping on stage

---

## PHASE 0 — Environment Setup
*Do this on every development machine. ~30 minutes.*

### Android SDK & Toolchain
- [ ] Android Studio Hedgehog (2023.1.1) or newer installed
- [ ] Android SDK Platform 35 (API 35) installed via SDK Manager
- [ ] Android SDK Build-Tools 35.0.0 installed
- [ ] Android NDK r25c or newer installed (`$ANDROID_NDK` env var set)
- [ ] CMake 3.22.1 installed (via SDK Manager → SDK Tools → CMake)
- [ ] JDK 17 set as project SDK in Android Studio
- [ ] `adb` available in PATH (`adb version` prints without error)
- [ ] `python3` available (`python3 --version` prints 3.9+)

### Project Import
- [ ] Unzip `lumi-android-final.zip` into a clean directory (no spaces in path)
- [ ] Copy `local.properties.template` → `local.properties`, fill in `sdk.dir`
- [ ] Open project root in Android Studio (File → Open → select `lumi-android/`)
- [ ] Gradle sync completes without error (`Build → Sync Project with Gradle Files`)
- [ ] `./gradlew assembleDebug` runs without error from terminal
- [ ] Zero Kotlin compilation errors in Build output

---

## PHASE 1 — whisper.cpp Native Library
*Critical path — ASR will not work without this. ~2 hours.*

### Build libwhisper.so
- [ ] Clone whisper.cpp: `git clone https://github.com/ggerganov/whisper.cpp`
- [ ] Verify NDK path: `echo $ANDROID_NDK` returns a valid path
- [ ] Create build dir: `cd whisper.cpp && mkdir build-android && cd build-android`
- [ ] Run CMake:
  ```bash
  cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DGGML_VULKAN=ON \
    -DWHISPER_BUILD_TESTS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF
  ```
- [ ] CMake exits successfully (no missing dependency errors)
- [ ] Run: `make -j$(nproc)` — compiles successfully
- [ ] Copy output `.so` files to project:
  - [ ] `libwhisper.so` → `app/src/main/jniLibs/arm64-v8a/`
  - [ ] `ggml/src/libggml.so` → `app/src/main/jniLibs/arm64-v8a/`
  - [ ] `ggml/src/libggml-base.so` → `app/src/main/jniLibs/arm64-v8a/`
  - [ ] `ggml/src/libggml-cpu.so` → `app/src/main/jniLibs/arm64-v8a/`
  - [ ] `ggml/src/libggml-vulkan.so` → `app/src/main/jniLibs/arm64-v8a/` *(if Vulkan built)*

### Integrate JNI Bindings
- [ ] Copy Kotlin JNI wrapper from `whisper.cpp/examples/whisper.android/app/src/main/java/com/whispercpp/whisper/WhisperLib.kt`
- [ ] Update package to `ai.lumi.voice`
- [ ] Replace stub methods in `WhisperJNI.kt` with real JNI calls from WhisperLib
- [ ] Map `initContext()`, `freeContext()`, `transcribeWithParams()`, `detectLanguage()` correctly
- [ ] `./gradlew assembleDebug` still compiles after JNI changes
- [ ] Install APK on iQOO 15 — `System.loadLibrary("whisper")` does NOT crash

### Verify ASR Works on Device
- [ ] Record 3 seconds of Hindi speech on device
- [ ] `HindiWhisperEngine.transcribe()` returns non-empty text
- [ ] `TurboWhisperEngine.detectLanguage()` returns `{"language":"hi","confidence":>0.8}`
- [ ] English utterance: `detectLanguage()` returns `"en"` with confidence > 0.8
- [ ] Hindi utterance: `WhisperEngine.detectAndRoute()` routes to `HindiWhisperEngine`, NOT Turbo
- [ ] Transcription latency for 5s Hindi clip: < 800ms on iQOO 15

---

## PHASE 2 — Model Downloads
*Download all models before the hackathon. Venue WiFi is unreliable. ~1–3 hours.*

### Hindi ASR — vasista22/whisper-hindi-medium-Q4 (~424 MB)
- [ ] Download from: `https://huggingface.co/vasista22/whisper-hindi-medium`
- [ ] Filename: `ggml-model-q4_0.bin`
- [ ] Saved to device path: `<app-files>/models/asr/whisper_hindi_medium_q4.bin`
- [ ] File size: ~424 MB (verify — corrupt file = silent crash)
- [ ] SHA-256 computed and updated in `ModelSpec.WHISPER_HINDI_MEDIUM_Q4.sha256`

### English ASR — Whisper Large v3 Turbo Q4 (~800 MB)
- [ ] Download from: `https://huggingface.co/ggerganov/whisper.cpp`
- [ ] Filename: `ggml-large-v3-turbo-q4_0.bin`
- [ ] Saved to device: `<app-files>/models/asr/whisper_large_v3_turbo_q4.gguf`
- [ ] File size: ~800 MB
- [ ] SHA-256 computed and updated in `ModelSpec.WHISPER_LARGE_V3_TURBO_Q4.sha256`

### Fast-path VLM — Moondream2 Q4 GGUF (~700 MB)
- [ ] Download from: `https://huggingface.co/vikhyatk/moondream2`
- [ ] Filename: `moondream2-int4.gguf`
- [ ] Saved to device: `<app-files>/models/vlm/moondream2_q4.gguf`
- [ ] SHA-256 updated in `ModelSpec.MOONDREAM2_Q4.sha256`

### Flagship VLM — Qwen3-VL-4B via Qualcomm AI Hub (~2.5 GB) *(flagship only)*
- [ ] Create account at `https://aihub.qualcomm.com`
- [ ] Find Qwen3-VL-4B-Instruct model page
- [ ] Select compile target: `Snapdragon 8 Elite Gen 5 (SM8850-AC)`
- [ ] Download compiled bundle (`.zip` or directory)
- [ ] Saved to device: `<app-files>/models/vlm/qwen3_vl_4b_instruct_qairt/`
- [ ] SHA-256 updated in `ModelSpec.QWEN3_VL_4B_QAIRT.sha256`

### Memory LLM — Qwen3-1.7B Q4 (~1 GB) *(flagship only)*
- [ ] Download from: `https://huggingface.co/Qwen/Qwen3-1.7B-GGUF`
- [ ] Filename: `Qwen3-1.7B-Q4_K_M.gguf`
- [ ] Saved to device: `<app-files>/models/llm/qwen3_1_7b_q4.gguf`
- [ ] SHA-256 updated in `ModelSpec.QWEN3_1_7B_Q4.sha256`

### USB Backup (CRITICAL for hackathon)
- [ ] All model files copied to USB-C drive
- [ ] USB-C drive labelled clearly and packed in bag
- [ ] `ModelDownloadManager.sideloadFromPath()` tested with USB path
- [ ] File-picker fallback flow tested end-to-end on iQOO 15

---

## PHASE 3 — TTS Setup

### Kokoro-82M English TTS
- [ ] Download `kokoro_82m_en.onnx` from `https://huggingface.co/hexgrad/Kokoro-82M`
- [ ] Add ONNX Runtime dependency to `app/build.gradle.kts`:
  ```kotlin
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
  ```
- [ ] Place `kokoro_82m_en.onnx` in `app/src/main/assets/models/tts/`
- [ ] Replace `KokoroTTS.synthesizeMock()` with real ONNX inference
- [ ] English TTS produces audible speech (not silence)
- [ ] First-audio latency: < 200ms on iQOO 15 (pre-warmed)

### Hindi System TTS
- [ ] On iQOO 15: Settings → General Management → Language → Text-to-speech
- [ ] Google TTS engine selected (not Samsung)
- [ ] Hindi (India) language pack downloaded and installed
- [ ] `SystemTTSWrapper.speak("Yahan tap karo", "hi")` produces audible Hindi speech
- [ ] Speaking rate set to 0.85 (slightly slower for elderly users)
- [ ] `awaitReady()` returns `true` within 2 seconds of app start

### Pre-recorded Audio Files
- [ ] Run: `python3 scripts/generate_audio.py --out app/src/main/res/raw/`
  - OR record manually with a warm, clear voice
- [ ] Replace silent placeholders with real audio:
  - [ ] `audio_chime.wav` — soft ascending two-tone chime (~0.4s)
  - [ ] `audio_checking.wav` — "Let me check your screen..." (~1.2s)
  - [ ] `audio_moment.wav` — "One moment..." (~0.8s)
  - [ ] `audio_cancel.wav` — "Okay, I stopped." (~0.8s)
- [ ] All 4 files play correctly on iQOO 15 without distortion
- [ ] Total playback time from bubble tap to first audio: < 300ms

---

## PHASE 4 — Qualcomm GenieX SDK *(for NPU inference)*
*Required for Qwen3-VL-4B on Hexagon NPU. Without it, VLM falls back to mock.*

- [ ] Request GenieX SDK access from Qualcomm Developer portal (NDA)
- [ ] Download `genieX.aar` and dependencies
- [ ] Place `genieX.aar` in `app/libs/`
- [ ] Uncomment in `app/build.gradle.kts`:
  ```kotlin
  implementation(files("libs/genieX.aar"))
  ```
- [ ] Replace `MockQairtRuntime` in `GenieXRuntime.kt` with real Qualcomm session:
  ```kotlin
  // QualcommGenieXQairtSession(modelFile)
  ```
- [ ] Replace `MockTextLLMRuntime` with real GenieX text session
- [ ] `System.loadLibrary("genieX_jni")` does NOT crash in `isGenieXAvailable()`
- [ ] Qwen3-VL-4B inference produces valid JSON output on an unknown screen
- [ ] NPU inference latency: 3–5 seconds for Qwen3-VL-4B on iQOO 15
- [ ] Moondream2 via GenieX llama_cpp: < 2 seconds on iQOO 15

---

## PHASE 5 — OriginOS 6 Compatibility
*The iQOO 15 runs OriginOS 6. Behaviour differs from stock Android in critical ways.*

### Service Survival
- [ ] Battery optimisation exemption granted: Settings → Battery → App launch → Lumi → Manual → toggle all ON
- [ ] `OverlayService` survives screen-off for 10 minutes (run, lock phone, wait, unlock — bubble still visible)
- [ ] `LumiAccessibilityService` survives after 10 minutes background (check `instance != null`)
- [ ] `VoiceService` does not get killed mid-recording
- [ ] Re-enable prompt shows correct OriginOS 6 Settings path (NOT stock Android path):
  - Stock: Settings → Accessibility → Downloaded apps → Lumi
  - OriginOS 6: **Settings → Additional settings → Accessibility → Installed apps → Lumi**
  - [ ] Update `OnboardingActivity.openAccessibilitySettings()` if path differs

### Overlay Permission
- [ ] `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` opens correct OriginOS screen
- [ ] Granting permission: bubble appears within 2 seconds
- [ ] Bubble survives OriginOS "clear all recent apps" action

### TYPE_ACCESSIBILITY_OVERLAY on Android 16
- [ ] Cursor window added via `TYPE_ACCESSIBILITY_OVERLAY` does NOT crash
- [ ] Cursor passes touches through to underlying app (tap on PhonePe button works while cursor is visible)
- [ ] Cursor is visible above full-screen apps (YouTube, PhonePe)
- [ ] Cursor hides correctly on FLAG_SECURE screens (UPI PIN screen)
- [ ] `BubbleView` (TYPE_APPLICATION_OVERLAY) does NOT appear on lock screen

### MediaProjection on OriginOS 6
- [ ] `ScreenCaptureService` MediaProjection permission dialog appears correctly
- [ ] Permission granted — `ScreenCaptureService.latestFrame` is non-null within 2 seconds
- [ ] Screenshot resolution: verify 1280×720 in `ScreenshotProcessor`
- [ ] FLAG_SECURE detection: open PhonePe PIN screen → no screenshot taken → voice says "I can't see this screen to protect your privacy"

---

## PHASE 6 — Bundle Map pHash Values
*The most critical demo fix. Without real hashes, every PhonePe step hits VLM (5s per step instead of <200ms).*

### Capture Real hashes on iQOO 15
- [ ] Connect iQOO 15 via USB with USB debugging enabled
- [ ] `adb devices` shows device
- [ ] Run: `python3 scripts/capture_bundle_hashes.py --app all`
  - OR manually follow steps below
- [ ] **Manual capture procedure:**
  - [ ] Open PhonePe on iQOO 15, navigate to home screen
  - [ ] `adb exec-out screencap -p > phonepe_home.png`
  - [ ] Run pHash on screenshot using the Python script
  - [ ] Record hash value; update `phonepe_maps.json` screen `PhonePe_Home.screenHash`
  - [ ] Repeat for: RecipientEntry, RecipientSelected, AmountEntry, ProceedToPay, UPIPinEntry
  - [ ] Repeat full flow for WhatsApp (ChatList, ContactSearch, ChatWindow, MessageTyped)
  - [ ] Update `settings_maps.json` for Settings_Main and Settings_WiFi
- [ ] All `screenHash` fields in all 3 JSON files updated with REAL values
- [ ] Rebuild APK: `./gradlew assembleDebug`
- [ ] Install on iQOO 15: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] Open PhonePe, activate Lumi, speak "send money" → bundle hit log appears (< 200ms, no VLM call)

### Verify Bundle Fast-path Performance
- [ ] Step 1 (Pay button): cursor arrives in < 1.2s from bubble tap ✓
- [ ] Step 2 (Recipient field): cursor arrives in < 200ms after screen change ✓
- [ ] Step 3 (Proceed): cursor arrives in < 200ms ✓
- [ ] Step 4 (Amount field): cursor arrives in < 200ms ✓
- [ ] Step 5 (Proceed to Pay): cursor arrives in < 200ms ✓
- [ ] Step 6 (UPI PIN): cursor arrives at correct keypad position ✓
- [ ] Logcat shows `UIMapRepo: HIT — PhonePe_*` for every step (not VLM inference)

---

## PHASE 7 — Onboarding Flow
*Test on a fresh iQOO 15 install — no prior permissions granted.*

- [ ] Clear app data: Settings → Apps → Lumi → Clear data
- [ ] Open Lumi — WelcomeScreen appears, cursor auto-points to "Let's begin" button
- [ ] Screen 1 — Overlay permission: cursor guides to Settings toggle
- [ ] Overlay granted — cursor visible back in Lumi immediately
- [ ] Screen 2 — Accessibility: OriginOS 6 Settings path navigated correctly by cursor
- [ ] Accessibility service enabled — `LumiAccessibilityService.instance != null`
- [ ] Screen 3 — Microphone: permission dialog appears and resolves
- [ ] Screen 4 — Language grid: tap हिंदी → highlights correctly, no voice needed
- [ ] Screen 5 — Model download: progress bars animate, WiFi warning shows on mobile data
- [ ] Screen 5 — Sideload path: "Load from device" opens file picker, accepts USB-drive path
- [ ] Screen 6 — Voice verification: bubble enters listening state, transcribes name, plays "Nice to meet you, [name]"
- [ ] Screen 7 — App mapping: PhonePe ✓ and WhatsApp ✓ appear instantly (bundled)
- [ ] Screen 8 — Mini demo: cursor navigates to WiFi toggle in Settings
- [ ] Onboarding complete → `preferences.onboardingComplete = true` → HomeScreen appears
- [ ] Bubble visible on HomeScreen and over all subsequent apps
- [ ] Second launch (app restart) → goes directly to HomeScreen (skips onboarding)

---

## PHASE 8 — Core Demo Flow End-to-End
*Run this 10 times in a row without failure before the hackathon.*

### Setup
- [ ] PhonePe installed and logged into a real account on iQOO 15
- [ ] At least one UPI contact/recipient saved in PhonePe
- [ ] iQOO 15 in Do Not Disturb mode (no notification interruptions)
- [ ] Phone volume at 80% — TTS audible across the room
- [ ] Screen brightness at maximum
- [ ] Phone orientation locked to portrait
- [ ] Battery > 80%

### The 90-second PhonePe Demo
Run 10 consecutive times — mark each run:
- [ ] Run 1 — bubble tap → chime < 300ms ✓
- [ ] Run 2 — Hindi ASR transcribes "PhonePe par 500 rupaye bhejne mein help karo" correctly ✓
- [ ] Run 3 — cursor animates to Pay button with Bezier arc ✓
- [ ] Run 4 — Hindi voice "Yahan tap karo — yeh Pay button hai" plays clearly ✓
- [ ] Run 5 — screen change detected → cursor auto-moves to recipient field ✓
- [ ] Run 6 — all 6 steps complete without manual intervention ✓
- [ ] Run 7 — total demo time: ≤ 90 seconds ✓
- [ ] Run 8 — "Kaam ho gaya!" plays on completion ✓
- [ ] Run 9 — bubble returns to Idle state after 2 seconds ✓
- [ ] Run 10 — full flow works flawlessly ✓

### Edge Cases to Test
- [ ] User taps bubble mid-guidance → guidance cancels, "Okay, I stopped." plays
- [ ] Unknown screen appears → VLM inference runs, cursor still finds correct element
- [ ] PIN screen (FLAG_SECURE) → cursor hides, voice says "can't see this screen"
- [ ] Low memory scenario: close apps, run Lumi — services still start
- [ ] App goes to background and back → bubble still visible

---

## PHASE 9 — Voice Pipeline Verification

- [ ] Speak Hindi into phone → `LanguageDetector` returns `"hi"` with confidence > 0.75
- [ ] Speak English into phone → `LanguageDetector` returns `"en"` with confidence > 0.75
- [ ] Hindi transcription WER: speak "PhonePe par paanch sau rupaye bhejo" → transcribed correctly
- [ ] Hinglish code-switching: "PhonePe pe 500 send karo" → handled by Turbo (no crash)
- [ ] VAD: speak for 3 seconds, pause → recording auto-stops within 1.5 seconds of silence
- [ ] Background noise rejection: speak with TV on → transcription still intelligible
- [ ] VoiceService does not continue recording after cancel
- [ ] TTS does not play over itself (flush queue on new instruction)

---

## PHASE 10 — Memory System

- [ ] Say name in voice verification → `ProfileManager.getName()` returns it on next launch
- [ ] Change language in settings → `preferredLanguage` updated in DataStore
- [ ] Form field memory: type name in any form → recalled on next similar form
- [ ] Memory Settings screen shows all stored profile keys
- [ ] "Clear all memories" wipes profile and form memory → confirmed empty in DB
- [ ] `MemoryRepository.extractAndStore()` runs post-task on FLAGSHIP tier without crash

---

## PHASE 11 — Adaptive Intelligence Mode (Cloud Fallback)

- [ ] Get Anthropic API key (pre-loaded — `sk-ant-...`)
- [ ] `SecureKeyStore.init(context)` called in `LumiApplication.onCreate()`
- [ ] Enter key in Settings → Adaptive Intelligence Mode → key saved in EncryptedSharedPreferences
- [ ] Toggle enabled → `preferences.adaptiveModeEnabled = true`
- [ ] Disable all on-device models (rename model files) → Lumi falls back to Anthropic API
- [ ] Anthropic response correctly parsed → cursor moves to correct element
- [ ] Re-enable on-device models (rename back)
- [ ] Key pre-loaded on demo phone as silent backup
- [ ] **Practice the fallback demo** — if on-device fails on stage, switch to Adaptive Mode smoothly without mentioning it to jury

---

## PHASE 12 — HackTracker & Office Kit Scoring

*15% of total score from HackTracker device data.*

### Office Kit Setup
- [ ] Download Office Kit on laptop from `pc.vivoglobal.com`
- [ ] Pair iQOO 15 to laptop via Office Kit (cover in Saturday 10:00 teach-in)
- [ ] Screen mirror working: phone UI visible on laptop display
- [ ] Remote control working: laptop keyboard controls phone
- [ ] File transfer tested: drag model file from USB to phone via Office Kit
- [ ] Shared clipboard: copy ADB command on laptop → paste on phone

### HackTracker Maximisation Strategy
- [ ] During Red Light (phone-only), use Lumi on the iQOO 15 to do real tasks:
  - Navigate WhatsApp to send a message to a teammate
  - Open Settings via Lumi to change WiFi
  - Each Lumi interaction = camera + voice + NPU → max HackTracker score
- [ ] Camera used for ≥ 60% of build time (ScreenCaptureService running = camera active)
- [ ] Voice used for ≥ 50% of build time
- [ ] Office Kit screen mirror kept active during all Green Light build blocks

---

## PHASE 13 — Pitch Rehearsal

### 3-Minute Pitch (memorise this exactly)
- [ ] 0:00–0:20 — "My mother calls me four times a week asking how to use her phone. This is what she would see if Lumi were on her phone."
- [ ] 0:20–0:40 — "No existing app does this offline, in Hindi, with a cursor showing exactly where to tap."
- [ ] 0:40–1:15 — **LIVE DEMO** — PhonePe flow, Hindi voice, cursor visible on projection
- [ ] 1:15–1:35 — "Qwen3-VL-4B on Qualcomm Hexagon NPU via GenieX. Fine-tuned Hindi Whisper. All on this phone."
- [ ] 1:35–1:55 — "650 million smartphone users in India. 1.5 billion globally. No offline alternative exists."
- [ ] 1:55–2:20 — "The UI map cache means a ₹8,000 phone gets the same < 200ms guidance as the iQOO 15."
- [ ] 2:20–2:45 — "Marathi, Tamil, Bengali next. Wake word. Auto-tap. This is phone literacy infrastructure."
- [ ] 2:45–3:00 — "Lumi. Your phone, explained."

### Rehearsal Log (10 runs required)
- [ ] Rehearsal 1 — Time: ___ mins ___s
- [ ] Rehearsal 2 — Time: ___ mins ___s
- [ ] Rehearsal 3 — Time: ___ mins ___s
- [ ] Rehearsal 4 — Time: ___ mins ___s
- [ ] Rehearsal 5 — Time: ___ mins ___s
- [ ] Rehearsal 6 — Time: ___ mins ___s
- [ ] Rehearsal 7 — Time: ___ mins ___s
- [ ] Rehearsal 8 — Time: ___ mins ___s
- [ ] Rehearsal 9 — Time: ___ mins ___s
- [ ] Rehearsal 10 — Time: ___ mins ___s — TARGET ≤ 3:00

### Jury Q&A Prep
- [ ] "Isn't this just Google Gemini?" → "Gemini requires internet, a Google account, and doesn't show you where to tap. Lumi is offline, Hindi-native, cursor-guided, and remembers you. It also works on a ₹8,000 phone."
- [ ] "What about accessibility features built into Android?" → "Built-in tools help users with disabilities use their phone. Lumi teaches first-time users to use any app, step by step, in their language."
- [ ] "How is this different from YouTube tutorials?" → "You'd have to leave the app you're trying to use. Lumi guides you inside the app, in real-time."
- [ ] "Does this work without internet?" → "Completely. Zero network calls. Everything runs on the Hexagon NPU."
- [ ] "What about privacy?" → "Screenshots never leave the device. Audio is discarded after transcription. No account required. No server."
- [ ] Qualcomm mentor (Kartikey Rawat): say "GenieX qairt runtime", "Hexagon NPU", "SM8850-AC", "AI Hub bundle" — he will recognise all of these immediately.

---

## PHASE 14 — Night-Before Checklist (September 4 evening)

- [ ] All models pre-downloaded on iQOO 15 (`ModelDownloadManager.isDownloaded()` returns true for all)
- [ ] All models copied to USB-C drive as backup
- [ ] APK installed fresh on iQOO 15 (clean install — not update)
- [ ] Onboarding completed on iQOO 15 with real permissions
- [ ] PhonePe logged in on iQOO 15 with at least one saved contact
- [ ] 90-second demo run successfully 3 times on iQOO 15 at home
- [ ] Anthropic API key pre-loaded in Adaptive Intelligence Mode
- [ ] Office Kit downloaded and paired on team laptop
- [ ] iQOO 15 battery charged to 100%
- [ ] USB-C drive in bag
- [ ] Charging cable in bag
- [ ] Backup USB-C cable in bag
- [ ] Team ID cards / student IDs packed
- [ ] Arrive at venue by 08:00 for check-in

---

## PHASE 15 — Day-Of Checklist (September 5, hackathon morning)

- [ ] 08:00 — Check in, receive loaner iQOO 15
- [ ] Check loaner phone: confirm Snapdragon 8 Elite Gen 5 (`Settings → About → Processor`)
- [ ] Install APK on loaner phone from USB drive
- [ ] Run UIMapWorker on loaner phone for bundle map loading
- [ ] Confirm bundle maps loaded: open PhonePe, trigger Lumi, verify HIT in logcat
- [ ] Confirm Hindi TTS works on loaner phone (may need to install language pack)
- [ ] Pair loaner phone to team laptop via Office Kit
- [ ] Run 90-second demo on loaner phone once — confirm working
- [ ] 10:00 — Clock starts. Go.

---

## Summary: What blocks the demo if missing

| Item | Impact if missing | Fix time |
|---|---|---|
| `libwhisper.so` | ASR uses mock (hardcoded text) — demo works but is fake | 2 hours |
| Real pHash values | Every step hits VLM (5s delay per step) — demo too slow | 1 hour |
| Hindi TTS pack | System TTS falls back to English on Hindi instructions | 10 minutes |
| Real audio files | Loading states are silent — less polished | 30 minutes |
| GenieX SDK | VLM uses mock (canned response) — cursor still moves correctly | 2–4 hours |
| Model files | On-device inference disabled — Adaptive Mode (Anthropic) required | 1–3 hours download |
| Kokoro TTS | English TTS falls back to System TTS — still works | 1 hour |

*The minimum viable demo needs: libwhisper.so + real pHash values + models downloaded. Everything else degrades gracefully.*

---

*Last updated: September 4, 2026 · Team html · iQOO Hackathon 2026 Pune*
