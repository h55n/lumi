# Lumi — Your phone, explained.

> On-device · Offline-first · Multilingual · Cursor-guided Android assistant

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-10%2B-green)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

## What Lumi does

Lumi is a persistent Android overlay that uses an on-device vision-language model to read your screen and guide you step-by-step — in your language, with a visible pointing cursor — to complete any task on your phone.

**No internet. No English. No confusion.**

### Demo: PhonePe UPI payment in Hindi (90 seconds, fully offline)

```
User says: "PhonePe par 500 rupaye bhejne mein help karo"
           (Help me send ₹500 on PhonePe)

Step 1: Cursor → Pay button        "Yahan tap karo — yeh Pay button hai"
Step 2: Cursor → recipient field   "Yahan recipient ka number ya UPI ID likhein"
Step 3: Cursor → Proceed           "Aage badhne ke liye Proceed tap karo"
Step 4: Cursor → amount field      "Paanch sau — 500 — yahan likhein"
Step 5: Cursor → Proceed to Pay    "Aur yahan tap karo"
Step 6: Cursor → UPI PIN keypad    "Apna UPI PIN daalen — payment ho jayegi"
```

---

## Architecture

```
User tap → OverlayService (bubble) → TaskEngine
  → VoiceService (Whisper ASR)
  → UIMapRepository (bundle/cache hit <100ms)
  → VLMEngine (Qwen3-VL-4B, 3–5s for unknown screens)
  → ElementFinder (Accessibility tree)
  → LumiAccessibilityService (cursor, TYPE_ACCESSIBILITY_OVERLAY)
  → TTSEngine (Kokoro / System TTS)
  → User taps → AccessibilityEvent → next step
```

## Model stack

| Component | Model | Runtime | Size |
|---|---|---|---|
| VLM (flagship) | Qwen3-VL-4B-Instruct | GenieX qairt (Hexagon NPU) | ~2.5 GB |
| VLM (budget/fast-path) | Moondream2 Q4 | GenieX llama_cpp / llama.cpp+Vulkan | ~700 MB |
| Hindi ASR | vasista22/whisper-hindi-medium-Q4 | whisper.cpp + Vulkan | ~424 MB |
| English ASR | Whisper Large v3 Turbo Q4 | GenieX llama_cpp | ~800 MB |
| English TTS | Kokoro-82M | ONNX Runtime (bundled) | 82 MB |
| Indian TTS | Android System TTS | Built-in | 0 MB |
| Memory LLM | Qwen3-1.7B Q4 | GenieX llama_cpp | ~1 GB |

---

## Getting started

### Prerequisites
- Android Studio Hedgehog or later
- Android NDK r25+
- JDK 17
- iQOO 15 (for demo) or any Android 10+ device with ≥4 GB RAM

### Build

```bash
git clone https://github.com/your-org/lumi-android
cd lumi-android
./gradlew assembleDebug
```

### Required setup before first run

#### 1. whisper.cpp JNI bindings
```bash
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp
mkdir build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DGGML_VULKAN=ON
make -j8
cp libwhisper.so ../lumi-android/app/src/main/jniLibs/arm64-v8a/
```

#### 2. GenieX SDK (for Qwen3-VL-4B on NPU)
See [BOOTSTRAP_PROMPT.md](BOOTSTRAP_PROMPT.md) for full integration steps.
Without this, VLM inference uses the mock implementation (returns canned responses).

#### 3. Kokoro TTS
Download `kokoro_82m_en.onnx` from [HuggingFace](https://huggingface.co/hexgrad/Kokoro-82M) and place in `app/src/main/assets/models/tts/`.
Without this, English TTS falls back to Android System TTS.

#### 4. Model downloads
On first launch, Lumi downloads models over WiFi (~1–5.5 GB depending on device tier).
For offline/hackathon use, pre-download models and sideload via USB:
1. Place model files in the correct subdirectory on the device
2. In the Model Download screen, use "Load from device" fallback

---

## Project structure

```
app/src/main/kotlin/ai/lumi/
├── LumiApplication.kt            Hilt app entry point
├── MainActivity.kt               Launcher — routes to onboarding or home
├── MainViewModel.kt
├── onboarding/                   8-screen self-demonstrating setup
├── overlay/                      Floating bubble + guidance bubble
├── cursor/                       Pointing hand cursor + Bezier animation
├── accessibility/                UI tree, element finder, event handling
├── screencapture/                MediaProjection session
├── engine/                       TaskEngine — the core guidance loop
├── inference/                    VLM, ASR model wrappers + ModelSelector
├── voice/                        Whisper ASR + Kokoro/System TTS
├── uimap/                        Bundle maps, Room cache, pHash matching
├── memory/                       Profile, form memory, vector search
├── cloud/                        Adaptive Intelligence Mode (Anthropic API)
├── settings/                     Language, bubble size, memory settings
├── data/                         Room DB, DAOs, entities, DataStore
├── di/                           Hilt modules
└── ui/                           Compose theme + HomeScreen + components
```

---

## Running tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

---

## Key design decisions

| Decision | Reason |
|---|---|
| TYPE_ACCESSIBILITY_OVERLAY for cursor | Android 12+ blocks touch pass-through for TYPE_APPLICATION_OVERLAY; ACCESSIBILITY_OVERLAY is trusted and exempt |
| pHash + Hamming distance for screen matching | O(1) lookup, handles minor UI changes (notifications, time, battery) |
| Bundle maps in APK assets | PhonePe PIN screen uses FLAG_SECURE — cannot be mapped at runtime |
| Pre-recorded loading audio | Eliminates TTS inference latency from user confirmation — <300ms always |
| System TTS for Hindi MVP | Pre-installed on Indian devices, <100ms latency, acceptable for short instructions |
| Adaptive Intelligence Mode framing | Positions cloud fallback as a feature, not a failure state |

---

## Hackathon: iQOO Hackathon 2026, Pune

- **Team:** html — Hassan Rehman, Mrunmayee Daware, Tanishq Mhetras
- **Track:** Productivity
- **Device:** iQOO 15 (Snapdragon 8 Elite Gen 5, OriginOS 6, Android 16)
- **Date:** September 5–6, 2026

See [BOOTSTRAP_PROMPT.md](BOOTSTRAP_PROMPT.md) for the complete pre-hackathon checklist.

---

## License

MIT — see [LICENSE](LICENSE)
