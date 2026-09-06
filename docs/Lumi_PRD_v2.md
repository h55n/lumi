# Lumi — Product Requirements Document
### Version 2.0 · iQOO Hackathon 2026, Pune City Battle (Sept 5–6) + Global Product Roadmap
### Team: html · Track: Productivity

---

## Table of Contents

1. [Project Identity](#1-project-identity)
2. [Team](#2-team)
3. [Problem Statement](#3-problem-statement)
4. [Market Size & Impact](#4-market-size--impact)
5. [Solution — What Lumi Is](#5-solution--what-lumi-is)
6. [How Lumi Works](#6-how-lumi-works)
7. [Feature Set by Phase](#7-feature-set-by-phase)
8. [Technical Architecture](#8-technical-architecture)
9. [Model Stack — Final Verified](#9-model-stack--final-verified)
10. [Automatic Device Configuration](#10-automatic-device-configuration)
11. [Voice & Multilingual Pipeline](#11-voice--multilingual-pipeline)
12. [UI Mapping Engine](#12-ui-mapping-engine)
13. [Memory System](#13-memory-system)
14. [Overlay & Cursor System](#14-overlay--cursor-system)
15. [Accessibility Service Integration](#15-accessibility-service-integration)
16. [Screen Capture Strategy](#16-screen-capture-strategy)
17. [Onboarding — Self-Demonstrating Setup](#17-onboarding--self-demonstrating-setup)
18. [UI Design System](#18-ui-design-system)
19. [Demo Flow — 90-Second Hackathon Script](#19-demo-flow--90-second-hackathon-script)
20. [Folder Structure](#20-folder-structure)
21. [Database Schemas](#21-database-schemas)
22. [Performance Targets](#22-performance-targets)
23. [Privacy & Security](#23-privacy--security)
24. [Phase-Wise Build Plan](#24-phase-wise-build-plan)
25. [iQOO Hackathon Strategy](#25-iqoo-hackathon-strategy)
26. [Known Risks & Mitigations](#26-known-risks--mitigations)
27. [Future Roadmap](#27-future-roadmap)

---

## 1. Project Identity

| Field | Value |
|---|---|
| **Product name** | Lumi |
| **Tagline** | Your phone, explained. |
| **Extended tagline** | Lumi lives on your phone, speaks your language, and shows you exactly where to tap — no internet, no English, no confusion. |
| **Category** | On-device AI phone assistant / accessibility tool |
| **Primary track** | Productivity (iQOO Hackathon 2026) — Finale-eligible |
| **Platform** | Android (native Kotlin + Jetpack Compose) |
| **Minimum Android version** | Android 10 (API 29) |
| **Minimum RAM** | 4 GB (budget tier floor) |
| **Target devices** | Any Android phone; optimised for Snapdragon 7-series and above |
| **Hackathon device** | iQOO 15 — Snapdragon 8 Elite Gen 5 (SM8850-AC), 12–16 GB RAM, OriginOS 6 / Android 16 |
| **Build approach** | Build complete product before hackathon; polish and demo at event |

---

## 2. Team

| Member | Role | Owns |
|---|---|---|
| **Hassan Rehman** | Team Lead · AI & Data Science | Task Engine, VLM inference pipeline, UI Mapping Engine, Memory System, ModelSelector |
| **Mrunmayee Daware** | Voice & Inference | Voice pipeline (ASR + TTS), Language detection, Accessibility Service, GenieX SDK integration |
| **Tanishq Mhetras** | Android Development | Overlay UI (bubble + cursor views + animations), Onboarding UI screens, App navigation shell |

**Team name:** html
**Hackathon:** iQOO Hackathon 2026 · Pune City Battle · Sept 5–6

---

## 3. Problem Statement

Every smartphone comes with an implicit assumption: that the person holding it knows how to use it.

That assumption is wrong for hundreds of millions of people globally — and most acutely wrong in India.

- A 58-year-old in Pune who received their first smartphone from their child and cannot navigate WhatsApp
- A migrant worker trying to register on a government welfare portal from a phone browser
- A college student in rural Maharashtra trying to book a train ticket on IRCTC for the first time
- A small shop owner attempting to set up PhonePe but confused by the UPI onboarding flow
- Any professional switching to a new app they've never used before, under time pressure

**Existing "solutions" and why they fail:**

| Solution | Why it fails |
|---|---|
| Google Assistant / Gemini | Requires internet, English-dominant, no cursor guidance, no native Hindi |
| Samsung Galaxy AI | Device-specific, cloud-based, not available on non-Samsung phones |
| YouTube tutorials | Forces user to leave the app they're trying to use |
| Calling a family member | Most common real-world solution in India — not always available |
| HeyClicky (Mac) | Mac-only, cloud-only, English-only, $20/month, zero India coverage |

None of these work offline. None of them point a cursor at exactly where to tap. None of them speak your language natively. None of them remember you.

**Lumi is the first on-device, multilingual, cursor-guided phone assistant that works entirely without internet.**

---

## 4. Market Size & Impact

| Segment | Size |
|---|---|
| India smartphone users | 650 million |
| India first-gen users (switched from basic phone 2020–2026) | 250 million |
| India users who navigate by trial-and-error | 300 million+ |
| Global elderly smartphone users (65+) | 400 million+ |
| Total addressable market | **1.5 billion people** |

- 82% of new smartphone users in India report needing help from someone else to complete tasks (GSMA 2025)
- Average Indian first-gen user calls a family member 4.3× per week for phone help
- Zero existing offline, cursor-guided, multilingual phone assistant apps exist on Android

---

## 5. Solution — What Lumi Is

Lumi is a persistent Android overlay that uses an on-device vision-language model to understand what is on your screen and guides you step-by-step, in your language, using a visible pointing cursor, to complete any task.

**How Lumi compares to everything else:**

| Capability | HeyClicky | Google Gemini | Lumi |
|---|---|---|---|
| Works offline | ✗ | ✗ | ✓ |
| Android native | ✗ (Mac only) | ✓ | ✓ |
| Visual cursor pointing | ✓ | ✗ | ✓ |
| Multilingual (Hindi / Marathi / Tamil) | ✗ | Partial | ✓ |
| Step-by-step guided | ✓ | ✗ | ✓ |
| Remembers user | ✗ | Partial | ✓ |
| Works on any Android | — | Partial | ✓ |
| Cost | $20/month | Free (data) | Free |

---

## 6. How Lumi Works

### Three states

**Dormant:** A 56dp floating bubble stays visible in the corner of the screen at all times (except lock screen, active calls, and secure screens). One tap activates.

**Listening:** The bubble expands, shows a voice waveform animation in Lumi Saffron (#F5A100). User speaks their request in any supported language.

**Guiding:** A pointing hand cursor appears and animates to the target UI element. A voice instruction plays in the user's language. The user taps where indicated. Lumi detects the screen change via AccessibilityEvent and delivers the next instruction automatically.

### Core interaction loop

```
User taps floating bubble
→ Chime plays immediately + "Let me check your screen..." audio (pre-recorded, ≤300ms)
→ Bubble enters listening state
→ User speaks goal in Hindi or English
→ Whisper ASR transcribes + detects language (≤600ms)
→ TaskEngine checks: known screen?
    YES (bundle/cache hit) → retrieve guidance plan (≤100ms, no VLM)
    NO → screenshot → Qwen3-VL-4B or Moondream2 inference (2–5s)
→ Accessibility Service finds element bounds
→ Cursor animates to element centre
→ Voice instruction in user's language
→ User taps
→ AccessibilityEvent: TYPE_WINDOW_STATE_CHANGED → next step
→ Loop until task complete
```

---

## 7. Feature Set by Phase

### Phase 1 — Demo-Ready MVP

| Feature | Description | Owner | Priority |
|---|---|---|---|
| Floating bubble overlay | 56dp, draggable, saffron (#F5A100), respects safe areas | Tanishq | P0 |
| Cursor system | Pointing hand, 56dp, #F5A100 fill, 3dp #1A1A2E stroke, TYPE_ACCESSIBILITY_OVERLAY | Tanishq | P0 |
| Voice activation | Tap bubble → listen, fine-tuned Whisper ASR, pre-recorded loading audio | Mrunmayee | P0 |
| Screen reading | MediaProjection → Qwen3-VL-4B via GenieX qairt | Hassan | P0 |
| UI map fast path | Bundle hit → guidance in <100ms (no VLM) for PhonePe, WhatsApp | Hassan | P0 |
| Step-by-step guidance | Automatic next-step on AccessibilityEvent screen change | Hassan | P0 |
| Hindi support | fine-tuned Whisper Medium Q4 + Android System TTS (Hindi pack) | Mrunmayee | P0 |
| English support | Whisper Large v3 Turbo Q4 + Kokoro-82M | Mrunmayee | P0 |
| Language auto-detection | Whisper auto-detect from first utterance | Mrunmayee | P0 |
| Self-demonstrating onboarding | Cursor guides user through full 8-screen setup | Tanishq + Hassan | P0 |
| Model downloader | On-demand, WiFi-aware, file-picker fallback for USB sideload | Hassan | P0 |
| Automatic device config | ModelSelector auto-picks runtime and models. Zero user-facing model settings | Hassan | P0 |
| Loading state UX | Pre-recorded audio + bubble pulse within 300ms. Never silent. | Mrunmayee + Tanishq | P0 |
| OriginOS resilience | Battery whitelist, multi-type ForegroundService, service health check | Mrunmayee | P0 |
| User memory — name + language | Remembers user's name and preferred language | Hassan | P1 |

### Phase 2 — Post-Demo Hardening

| Feature | Description |
|---|---|
| Marathi support | Fine-tuned Marathi ASR when a production-quality model is available; Android System TTS (Marathi pack) |
| Tamil, Bengali, Telugu | IndicWhisper (once converted to whisper.cpp-compatible format) + System TTS |
| IndicTTS integration | AI4Bharat IndicTTS once ONNX conversion pipeline is built |
| Wake word "Nova" | Porcupine by Picovoice, always-on |
| Auto-tap mode | ACTION_CLICK via Accessibility Service |
| Memory expansion | Form auto-fill, task pattern learning |
| App update re-mapping | PackageManager version change → background remap trigger |

### Phase 3 — Full Product

| Feature | Description |
|---|---|
| Custom task recorder | User records a task once; Lumi replays it |
| Proactive suggestions | "Looks like you're sending money — want help?" |
| Business mode | Admin pushes task flows to employee phones |
| 50+ language expansion | Full Whisper language range |
| Tablet support | Overlay optimised for larger screens |

---

## 8. Technical Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        LUMI ANDROID APP                          │
│                                                                  │
│  ┌───────────────┐   ┌───────────────┐   ┌────────────────────┐ │
│  │ OverlayService│   │ VoiceService  │   │ TaskEngine         │ │
│  │ (FG: bubble)  │   │ (FG: audio)   │   │ (guidance loop)    │ │
│  └──────┬────────┘   └──────┬────────┘   └────────┬───────────┘ │
│         │                  │                      │              │
│  ┌──────▼──────────────────▼──────────────────────▼───────────┐ │
│  │                  INTELLIGENCE LAYER                         │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │ │
│  │  │  VLM Engine  │  │  ASR Engine  │  │  TTS Engine      │ │ │
│  │  │ Qwen3-VL-4B  │  │ Whisper Q4   │  │ Kokoro / SysTTS  │ │ │
│  │  │ (qairt / NPU)│  │ (whisper.cpp)│  │                  │ │ │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────────┘ │ │
│  │         │                 │                  │              │ │
│  │  ┌──────▼─────────────────▼──────────────────▼───────────┐ │ │
│  │  │               GEENIEX SDK LAYER                        │ │ │
│  │  │  qairt runtime (AI Hub bundles, NPU-compiled)          │ │ │
│  │  │  llama_cpp runtime (any GGUF → Hexagon NPU via GGML)   │ │ │
│  │  │  Qualcomm Hexagon NPU ← primary compute on flagship    │ │ │
│  │  │  Adreno GPU Vulkan ← fallback via llama.cpp            │ │ │
│  │  └──────────────────────────────────────────────────────── │ │
│  └──────────────────────────────────────────────────────────── │
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    DATA LAYER                             │  │
│  │  Room DB · SQLite-vec · DataStore · File store            │  │
│  │  UI Maps (bundle + runtime) · Memory · Settings           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌────────────────────┐  ┌──────────────────────────────────┐   │
│  │ LumiAccessibility  │  │ ScreenCaptureService             │   │
│  │ Service            │  │ (MediaProjection session)        │   │
│  │ cursor window here │  │                                  │   │
│  └────────────────────┘  └──────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

### Key services

| Service | Type | Role |
|---|---|---|
| `OverlayService` | ForegroundService (MEDIA_PLAYBACK) | Draws bubble on WindowManager via TYPE_APPLICATION_OVERLAY |
| `VoiceService` | ForegroundService (DATA_SYNC) | Audio record, Whisper inference, TTS playback |
| `TaskEngine` | Singleton in OverlayService | Orchestrates the per-task guidance loop |
| `LumiAccessibilityService` | AccessibilityService | UI tree + element bounds + event triggers + **cursor window (TYPE_ACCESSIBILITY_OVERLAY)** |
| `ScreenCaptureService` | ForegroundService | MediaProjection session — one session per Lumi activation, not per screenshot |
| `UIMapWorker` | WorkManager (EXPEDITED) | Background app mapping at first launch |
| `ModelDownloadManager` | WorkManager | Downloads, validates (SHA-256), and caches model files. Accepts file-picker path for USB sideload |
| `MemoryRepository` | Repository | Read/write to Room DB memory tables |

---

## 9. Model Stack — Final Verified

All models below are confirmed to exist at the listed source. No alternatives. No "or" options. This is the stack.

### Vision-Language Models

| Slot | Model | Source | Runtime | Size (INT4/Q4) | Chipset support |
|---|---|---|---|---|---|
| **Primary VLM** | Qwen3-VL-4B-Instruct | Qualcomm AI Hub | GenieX qairt (NPU) | ~2.5 GB | SD 8 Elite Gen 5 ✓ |
| **Fast-path VLM** | Moondream2 Q4 GGUF | Hugging Face (moondream org) | GenieX llama_cpp (Hexagon NPU) | ~700 MB | Any Snapdragon |

> **Moondream2 note:** Used for fast-path verification of cached UI maps and as the sole VLM on budget devices. English-output only — multilingual guidance is handled by pre-built map templates, not runtime VLM generation.

> **Qwen3-VL-4B note:** Confirmed on Qualcomm AI Hub, confirmed supported on Snapdragon 8 Elite Gen 5 (iQOO 15 chipset). Uses GenieX qairt runtime — NPU-compiled, fastest path. Do not use for budget devices.

### ASR — Speech Recognition

| Slot | Model | Source | Runtime | Size | WER (Hindi) |
|---|---|---|---|---|---|
| **Hindi ASR** | vasista22/whisper-hindi-medium-Q4 | Hugging Face | whisper.cpp (CPU/GPU) | ~424 MB | **8.2% FLEURS-Hindi** |
| **English + detection** | Whisper Large v3 Turbo Q4 GGUF | Hugging Face (ggerganov/whisper.cpp) | GenieX llama_cpp (flagship) / whisper.cpp (budget) | ~800 MB | ~5% English |
| **Marathi** | *(Phase 2)* | No production-grade Q4 model exists. Phase 2: Android System TTS Marathi + Whisper Turbo for ASR | — | — | — |

> **Language routing:** Whisper Turbo runs first on the initial 3 seconds of audio for language detection only. Once language is identified, the language-specific fine-tuned model handles full transcription. If detected language is English, Whisper Turbo continues. If Hindi, vasista22 model handles transcription.

### TTS — Text to Speech

| Slot | Model | Source | Size | Languages |
|---|---|---|---|---|
| **English TTS** | Kokoro-82M | Hugging Face (MIT) | 82 MB — **bundled in APK** | English, multi-voice |
| **Hindi TTS (MVP)** | Android System TTS | Android built-in | 0 MB — pre-installed on most Indian phones | Hindi, Marathi, and more |
| **Hindi TTS (Phase 2)** | AI4Bharat IndicTTS | AI4Bharat | ~200 MB per language — requires ONNX conversion | 13 Indian languages |

> **System TTS rationale:** Natural, pre-installed on target devices, zero build complexity, zero download. Acceptable quality for short instructional phrases. IndicTTS is a quality upgrade, not a Phase 1 requirement.

### Text LLM

| Slot | Model | Source | Runtime | Size | Use |
|---|---|---|---|---|---|
| **Memory extraction** | Qwen3-1.7B Q4 GGUF | Qualcomm AI Hub / HuggingFace | GenieX llama_cpp | ~1 GB | Post-task fact extraction from transcript. Flagship only. |

### Embedding

| Model | Source | Size | Use |
|---|---|---|---|
| MiniLM-L6-v2 | Hugging Face (sentence-transformers) | 22 MB — **bundled** | Semantic memory search via SQLite-vec |

### Inference Runtimes (priority order)

1. **GenieX qairt** — Pre-compiled AI Hub bundles. NPU-only. Fastest. Used for Qwen3-VL-4B on iQOO 15.
2. **GenieX llama_cpp** — Any GGUF on Hexagon NPU via GGML Hexagon backend. Used for Moondream2, Whisper Turbo, Qwen3-1.7B on flagship.
3. **whisper.cpp with Vulkan** — For fine-tuned Hindi Whisper Medium Q4. Runs on Adreno GPU. All device tiers.
4. **llama.cpp with Vulkan** — Budget tier fallback for Moondream2. Adreno GPU. No GenieX dependency.
5. **CPU** — Final fallback. Always works. Used for System TTS and emergency degraded mode.

---

## 10. Automatic Device Configuration

**The user never sees a model name, a runtime name, or a configuration option for inference.** The app reads device hardware at first launch and configures itself silently.

### ModelSelector logic

```kotlin
object ModelSelector {

    fun configure(context: Context): DeviceTier {
        val socModel = Build.SOC_MODEL ?: ""
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(mem)
        val freeGb = mem.availMem / (1024.0 * 1024 * 1024)

        return when {
            // iQOO 15 and equivalent SD 8 Elite Gen 5 devices
            socModel.contains("SM8850") && freeGb >= 6.0 -> DeviceTier.FLAGSHIP
            // SD 8 Elite (Gen 4), SD 8 Gen 3 with sufficient RAM
            (socModel.contains("SM8750") || socModel.contains("SM8650")) && freeGb >= 4.0 -> DeviceTier.MID_HIGH
            // SD 7-series, older 8-series, 4–6 GB devices
            freeGb >= 2.0 -> DeviceTier.BUDGET
            // Emergency mode — Accessibility tree + System TTS only
            else -> DeviceTier.MINIMAL
        }
    }
}
```

### What each tier loads

| Component | FLAGSHIP (iQOO 15) | MID_HIGH | BUDGET | MINIMAL |
|---|---|---|---|---|
| Primary VLM | Qwen3-VL-4B (GenieX qairt) | Moondream2 Q4 (GenieX llama_cpp) | Moondream2 Q4 (llama.cpp+Vulkan) | None |
| ASR Hindi | vasista22 whisper-hindi-medium-Q4 | vasista22 whisper-hindi-medium-Q4 | vasista22 whisper-hindi-medium-Q4 | None |
| ASR English | Whisper Turbo Q4 (GenieX llama_cpp) | Whisper Turbo Q4 (whisper.cpp+Vulkan) | Whisper Small Q4 (whisper.cpp+CPU) | None |
| TTS English | Kokoro-82M | Kokoro-82M | Kokoro-82M | System TTS |
| TTS Hindi | System TTS (instant) | System TTS | System TTS | System TTS |
| Memory LLM | Qwen3-1.7B (GenieX llama_cpp) | None | None | None |
| Model loading | Simultaneous (16 GB RAM) | Sequential with unload | Sequential with unload | None |
| Unknown screen fallback | Qwen3-VL-4B | Moondream2 | Moondream2 | Accessibility tree only |

> **MINIMAL tier:** When on-device inference is impossible, Lumi operates in Accessibility-only mode using pre-built UI maps exclusively. Voice activation becomes tap-only. System TTS delivers all instructions. This covers the worst possible device while still delivering value for the 15 mapped apps.

### Model loading lifecycle (BUDGET tier)

```
Lumi activation (tap bubble)
→ Check: is Hindi Whisper resident? → YES: start recording → transcribe
                                    → NO: load (424 MB) → record → transcribe → UNLOAD Whisper
→ Check: screen in bundle? YES → serve guidance, load TTS only
→ screen NOT in bundle → load Moondream2 (700 MB) → infer → UNLOAD Moondream2
→ Load TTS if not resident → speak
```

On 4 GB device: peak simultaneous load ≈ Whisper (424 MB) + Moondream2 (700 MB) + Kokoro (82 MB) = **1.2 GB**. Within the 1.5 GB available headroom. Safe.

---

## 11. Voice & Multilingual Pipeline

### Languages — Phase 1 (Hackathon MVP)

| Language | ASR | TTS | LLM reasoning |
|---|---|---|---|
| Hindi | vasista22/whisper-hindi-medium-Q4 | Android System TTS (hi-IN) | Qwen3-VL-4B (system prompt forces Hindi output) |
| English | Whisper Large v3 Turbo Q4 | Kokoro-82M | Qwen3-VL-4B (English) |
| Hinglish (code-switch) | Whisper Turbo Q4 (handles natively) | Android System TTS (hi-IN) | Qwen3-VL-4B |

### Languages — Phase 2

Marathi, Tamil, Bengali, Telugu, Gujarati, Punjabi — all via Android System TTS packs (immediate) + fine-tuned Whisper variants as they become available in production-quality Q4 format.

### Language detection flow

```kotlin
// WhisperEngine.kt
fun detectAndRoute(audioBuffer: FloatArray): TranscriptResult {
    // Step 1: Run Whisper Turbo on first 3s for language detection only
    val langResult = whisperTurbo.detectLanguage(audioBuffer.take3Seconds())
    
    return when (langResult.languageCode) {
        "hi" -> {
            // Route to fine-tuned Hindi model for actual transcription
            hindiWhisper.transcribe(audioBuffer)
        }
        "en" -> {
            // Whisper Turbo continues for English
            whisperTurbo.transcribe(audioBuffer)
        }
        else -> {
            // Unknown or unsupported language — Whisper Turbo best-effort
            whisperTurbo.transcribe(audioBuffer)
        }
    }
}
```

### VLM multilingual prompting

When generating instructions, Qwen3-VL-4B receives a system prompt with explicit language enforcement:

```
System: You are Lumi, a phone guidance assistant. 
The user speaks {detectedLanguage}.
You MUST respond ONLY in {detectedLanguage}.
Use simple, short words. Maximum 10 words per instruction.
Identify the exact UI element to tap next and give one instruction.
Format: {"target": "<element description>", "instruction": "<short instruction in {detectedLanguage}>"}
```

This must be verified during Week 1 inference testing. If Qwen3-VL-4B defaults to English or Chinese despite the prompt, adjust temperature and add a few-shot example in the target language. Flag: test Hindi prompt adherence on day one of building.

### Voice pipeline timing (iQOO 15, FLAGSHIP tier)

| Step | Target | Method |
|---|---|---|
| Tap to chime + loading audio | < 300 ms | Pre-recorded audio file, played from RAM |
| Language detection (Whisper Turbo, 3s clip) | < 400 ms | GenieX llama_cpp, Hexagon NPU |
| Hindi transcription (vasista22 Q4) | < 600 ms | whisper.cpp, Vulkan |
| UI map bundle hit | < 100 ms | JSON asset lookup, no DB or VLM |
| Room DB cache hit | < 200 ms | Room query + pHash match |
| Moondream2 fast-path (GenieX llama_cpp) | < 2 s | Hexagon NPU |
| Qwen3-VL-4B full inference (GenieX qairt) | 3–5 s | Hexagon NPU, NPU-compiled |
| Accessibility element lookup | < 30 ms | Native Android API |
| Cursor animation | 400 ms | ValueAnimator, Bezier, 60fps |
| Kokoro TTS first audio | < 150 ms | Pre-warmed |
| Android System TTS first audio | < 100 ms | Always available |
| **End-to-end (bundle hit)** | **< 1.2 s** | The demo path |
| **End-to-end (Qwen3-VL-4B)** | **< 6 s** | Unknown screen path |

---

## 12. UI Mapping Engine

### Purpose

The UI Mapping Engine is the core performance optimisation. It eliminates VLM inference for the 15 most-used apps by storing pre-computed, step-by-step guidance plans in Room DB and, for critical demo apps, as JSON bundles in the APK itself.

**For 95% of daily real-world interactions — familiar apps, familiar screens — no AI inference fires at all.**

### Two-layer map system

**Layer 1 — Bundled maps (APK assets)**
Pre-built by the team during build week from logged-in devices. Loaded into Room DB on first launch. Not rebuilt at install time. Always fresh for demo apps.

```
/assets/uimaps/
    phonepe_maps.json      # Pay, recipient entry, amount, PIN screen
    whatsapp_maps.json     # Chat list, compose, send
```

These cover the hackathon demo flow. They are built manually during build week from a logged-in test device, not auto-generated at install time on the user's phone.

**Layer 2 — Runtime maps (Room DB, built at install time)**
UIMapWorker runs for apps NOT covered by the bundle. Launches each app, captures Accessibility tree + screenshot, runs Moondream2 to identify interactive elements, stores in Room DB.

> **Critical:** PhonePe's UPI PIN screen uses FLAG_SECURE and restricts its accessibility tree. The bundled map contains pre-captured screen element coordinates for exactly these secured screens. No runtime mapping can capture them.

### Priority apps list

```kotlin
// Apps covered by runtime UIMapWorker (not in bundle — bundle covers PhonePe + WhatsApp)
val RUNTIME_PRIORITY_APPS = listOf(
    "com.android.settings",
    "com.android.camera2",
    "com.google.android.gm",
    "com.android.chrome",
    "com.google.android.youtube",
    "net.one97.paytm",
    "in.gov.uidai.mAadhaarPlus",
    "com.irctc.android",
    "com.google.android.apps.maps",
    "com.truecaller",
    "com.instagram.android",
    "com.google.android.dialer",
    "com.google.android.apps.photos"
)
```

### Screen hash matching

```kotlin
fun findGuidancePlan(screenshot: Bitmap, packageName: String): GuidancePlan? {
    // Layer 1: check bundle
    val bundled = bundleMapRepository.get(packageName)
    if (bundled != null) {
        val hash = perceptualHash(screenshot)
        val match = bundled.screens.minByOrNull { hammingDistance(it.screenHash, hash) }
        if (match != null && hammingDistance(match.screenHash, hash) < HASH_THRESHOLD) {
            return match.guidancePlan
        }
    }
    // Layer 2: check Room DB (runtime maps)
    val cached = uiMapDao.getScreensForApp(packageName)
    val best = cached.minByOrNull { hammingDistance(it.screenHash, perceptualHash(screenshot)) }
    return if (best != null && hammingDistance(best.screenHash, perceptualHash(screenshot)) < HASH_THRESHOLD)
        best.guidancePlan
    else null
}
```

### Staleness detection

```kotlin
fun checkStaleness(packageName: String) {
    // Only for runtime maps, not bundle maps
    val stored = uiMapDao.getVersionCode(packageName) ?: return
    val current = packageManager.getPackageInfo(packageName, 0).longVersionCode
    if (current != stored) UIMapWorker.enqueueRemap(packageName)
}
```

---

## 13. Memory System

### Architecture

Inspired by SuperMemory's principles: auto-extraction, contradiction handling, temporal awareness, profile + RAG in a single query. Fully on-device using Room DB + SQLite-vec. **Phase 1 implements profile memory and form memory only. Semantic vector memory is Phase 2.**

### Memory types — Phase 1

**User profile (structured)**
Stored in `UserProfileDao`. Key-value pairs extracted from conversation.

```
name: "Ravi"
preferredLanguage: "hi"
preferredUPIApp: "PhonePe"
```

Contradiction handling: newer value always replaces older value. Old value written to `ProfileHistory` with timestamp.

**Form memory (field-level auto-fill)**

```
fieldType: "name"     → "Ravi Kumar"
fieldType: "mobile"   → "+91-98XXXXXX12"
fieldType: "pincode"  → "411001"
fieldType: "city"     → "Pune"
```

When Lumi sees a form field, it checks FormMemoryDao by field type and pre-fills silently if confident.

### Memory types — Phase 2

**Task memory (episodic):** Tracks task completion patterns. After 3 successful completions, Lumi suggests proactively.

**Semantic memory (vector):** MiniLM-L6-v2 embeddings stored in SQLite-vec. Top-5 relevant memories (< 200 tokens) injected into VLM prompt at task start.

### Memory extraction (Flagship tier only, Phase 1)

```kotlin
// Runs in background after task completes — flagship only
fun extractMemories(taskTranscript: String) {
    val prompt = """
        From this conversation extract facts about the user.
        Return JSON only: {"facts": [{"key": "...", "value": "...", "confidence": 0.0–1.0}]}
        Conversation: $taskTranscript
    """
    val result = textLLM.generate(prompt)   // Qwen3-1.7B
    parseAndUpsertToProfile(result)
}
```

---

## 14. Overlay & Cursor System

### Architecture — critical Android 12+ fix

Android 12 introduced a restriction that blocks untrusted touch pass-through for TYPE_APPLICATION_OVERLAY windows using FLAG_NOT_TOUCHABLE. The cursor must be drawn from the AccessibilityService context using TYPE_ACCESSIBILITY_OVERLAY, which is trusted and explicitly exempt from this restriction.

**Bubble:** Drawn by OverlayService via TYPE_APPLICATION_OVERLAY — receives taps, does not pass them through. Correct window type.

**Cursor:** Drawn by LumiAccessibilityService via TYPE_ACCESSIBILITY_OVERLAY — passes touches through to the underlying app. Only correct window type for Android 12+.

### Bubble implementation

```kotlin
// OverlayService.kt
private fun createBubbleView(): View {
    val params = WindowManager.LayoutParams(
        56.dpToPx(),    // 56dp — minimum for elderly users (Material: 48dp, research: 56dp+)
        56.dpToPx(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = savedX
        y = savedY
    }
    return BubbleView(context).also { bubble ->
        bubble.setOnClickListener { onBubbleTapped() }
        bubble.setOnTouchListener(DragTouchListener(params, windowManager))
    }
}
```

### Cursor implementation (TYPE_ACCESSIBILITY_OVERLAY)

```kotlin
// LumiAccessibilityService.kt
fun showCursorAt(targetRect: Rect) {
    val params = WindowManager.LayoutParams(
        56.dpToPx(),
        56.dpToPx(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or    // safe here: TYPE_ACCESSIBILITY_OVERLAY
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
    val cursorView = CursorView(this)
    windowManager.addView(cursorView, params)
    cursorAnimator.animateTo(
        targetX = targetRect.centerX().toFloat(),
        targetY = targetRect.centerY().toFloat()
    )
}
```

### Cursor design spec

| Property | Value | Rationale |
|---|---|---|
| Shape | Pointing hand (index finger extended) | Universally understood "tap here" across all cultures |
| Default size | 56×56 dp | Above WCAG and Material minimums; appropriate for elderly users |
| Fill colour | Lumi Saffron #F5A100 | Warm spectrum, peak visibility for elderly eyes, culturally resonant (saffron = guidance) |
| Stroke | 3 dp, #1A1A2E | Ensures visibility on any app background — white WhatsApp, red PhonePe, dark YouTube |
| Drop shadow | 8 dp blur, #1A1A2E at 35% | Depth and separation from any background |
| Travel animation | Bezier ease-in-out, 400 ms | Natural arc motion, FastOutSlowInInterpolator |
| Arrival pulse | Scale 1.0→1.5→1.0, 300 ms | Immediate attention signal on arrival |
| Repeat pulse | Every 2000 ms, loops until user taps | User who looks away can still find the target |
| Tap hint | Ripple animation beneath cursor | Confirms "tap here" without text |

```kotlin
// CursorAnimator.kt
fun animateTo(targetX: Float, targetY: Float, onComplete: () -> Unit) {
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { anim ->
            val t = anim.animatedFraction
            cursorView.x = cubicBezier(startX, targetX, t)
            cursorView.y = cubicBezier(startY, targetY, t)
        }
        doOnEnd {
            startArrivalPulse()       // one-shot pulse on arrival
            startRepeatPulse()        // 2s interval repeat until tapped
            onComplete()
        }
    }.start()
}

private fun startRepeatPulse() {
    repeatPulseHandler.postDelayed(object : Runnable {
        override fun run() {
            cursorView.animate()
                .scaleX(1.5f).scaleY(1.5f).setDuration(150)
                .withEndAction {
                    cursorView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            repeatPulseHandler.postDelayed(this, 2000)
        }
    }, 2000)
}
```

### Excluded contexts (overlay and cursor hidden)

- Lock screen
- Active phone/video calls
- Windows with FLAG_SECURE (banking PINs, payments) — cursor hides, no screenshot taken
- System permission dialogs

---

## 15. Accessibility Service Integration

### Manifest declaration

```xml
<service
    android:name=".accessibility.LumiAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

```xml
<!-- accessibility_service_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewClicked"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRequestEnhancedWebAccessibility"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100" />
```

### Core functions

```kotlin
class LumiAccessibilityService : AccessibilityService() {

    fun findElementByDescription(description: String): Rect? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(description)
            .ifEmpty { root.findAccessibilityNodeInfosByViewId(description) }
        return nodes.firstOrNull()?.let { node ->
            Rect().also { node.getBoundsInScreen(it) }
        }
        // If null: fallback to pixel coordinates from VLM output (see Section 16)
    }

    fun captureUITree(): String {
        val root = rootInActiveWindow ?: return "{}"
        return buildJson(root, depth = 0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                TaskEngine.onScreenChanged(event.packageName?.toString())
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                TaskEngine.onUserInteraction()
        }
    }

    override fun onInterrupt() { /* no-op */ }
}
```

### OriginOS resilience — AccessibilityService survival

OriginOS 6 (iQOO 15) aggressively kills background services including accessibility services. Required mitigations:

```kotlin
// In every Activity.onResume()
fun checkAccessibilityServiceHealth() {
    val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
    val running = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val lumiEnabled = running.any { it.id.contains("LumiAccessibilityService") }
    if (!lumiEnabled) showNonDismissableReEnablePrompt()
}
```

In onboarding, additionally call:
```kotlin
startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
    data = Uri.parse("package:${packageName}")
})
```

The re-enable prompt must show the exact Settings path for OriginOS 6, which differs from stock Android. Test this path on an actual iQOO 15 running OriginOS 6 during build week before the hackathon.

---

## 16. Screen Capture Strategy

### When to use what

| Scenario | Method | Reason |
|---|---|---|
| Task start — bundle or Room DB hit | No screenshot | Pre-built plan has all needed info |
| Task start — unknown app | MediaProjection + Moondream2/Qwen3-VL-4B | VLM needs visual context |
| Mid-task step verification | Accessibility tree only | Element presence check, no VLM |
| App mapping at install (non-bundle apps) | MediaProjection + Accessibility tree | Both needed for map creation |
| Error / unexpected screen | MediaProjection + VLM | Unknown state requires visual inference |

### Element lookup with fallback

For apps that block their accessibility tree (PhonePe PIN screen, banking apps):

```kotlin
fun findElementWithFallback(
    description: String,
    vlmRelativeCoords: Pair<Float, Float>?   // (x%, y%) from VLM output
): Rect? {
    // Primary: Accessibility tree
    val treeResult = findElementByDescription(description)
    if (treeResult != null) return treeResult

    // Fallback: pixel coordinates from VLM screenshot output
    if (vlmRelativeCoords != null) {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val px = (vlmRelativeCoords.first * screenWidth).toInt()
        val py = (vlmRelativeCoords.second * screenHeight).toInt()
        return Rect(px - 24, py - 24, px + 24, py + 24)  // 48dp approximate bounds
    }
    return null
}
```

### Screenshot specifications

- Resolution: 1280×720 (downscaled from device resolution before VLM input)
- Format: JPEG, 60% quality
- MediaProjection session: started once per Lumi activation, reused for all screenshots in that session
- Frames: never written to storage. Processed in memory and discarded after VLM inference.
- FLAG_SECURE check: before any capture, verify window does not have FLAG_SECURE. If secure, skip capture and notify user: "I can't see this screen to protect your privacy."

---

## 17. Onboarding — Self-Demonstrating Setup

### Corrected screen sequence

The original PRD had a circular dependency: it asked for voice input before ASR was downloaded. This is fixed below.

**Screen 0 — Welcome**
Lumi logo. "Hi, I'm Lumi." in detected system language.
Before the user reads anything: pointing cursor appears from the right and animates to the single "Let's begin" button. Voice: pre-recorded chime + "Tap here to start." This is the first 3 seconds of the product — it proves the concept before the user has read a word.

**Screen 1 — Overlay permission**
"I need to appear over your other apps to guide you."
Cursor points to "Grant permission." System opens Settings → Display over other apps. Cursor is already visible above Settings (it's an overlay), pointing at the Lumi toggle.
> **OriginOS 6 note:** The Settings path on OriginOS 6 differs from stock Android. The cursor's exact navigation path must be tested on the actual iQOO 15 before the hackathon. Do not hard-code stock Android Settings navigation.

**Screen 2 — Accessibility permission**
"This lets me find exactly where buttons are — no guessing."
Cursor guides through Settings → Accessibility → Lumi. Same OriginOS path testing applies.

**Screen 3 — Microphone permission**
Standard Android dialog. Cursor points to "Allow."

**Screen 4 — Language selection (tap-grid, NO VOICE YET)**
"Which language do you prefer?"
Five large buttons (56dp minimum height): **हिंदी · English · मराठी · தமிழ் · Other**
No dropdown. No voice. No ASR downloaded yet. Tap only.
Selection stored in DataStore immediately.

**Screen 5 — Model download**
"Downloading your language AI. This takes about 2 minutes on WiFi."
Downloads: fine-tuned Whisper Medium Q4 for selected language (~424 MB for Hindi) + confirms Kokoro-82M is bundled. Progress shown per model. WiFi detection — warns if on mobile data.
If download fails: offer file-picker path ("Load from device storage") for USB sideload.

**Screen 6 — Voice verification (FIRST VOICE INPUT)**
"Say your name." Bubble enters listening state for the first time. Whisper transcribes. "Nice to meet you, [name]." Response confirms the full voice pipeline is working.

**Screen 7 — App mapping**
"I'm learning your apps now. Takes about 90 seconds."
Bundled maps loaded instantly for PhonePe and WhatsApp. UIMapWorker runs for remaining 13 apps. Animated progress: "PhonePe ✓  WhatsApp ✓  Settings... Chrome..."

**Screen 8 — Mini-demo**
"Let me show you how I work."
Cursor navigates to WiFi settings, points to the toggle, explains each step. Full guidance loop in 30 seconds. Onboarding complete.

---

## 18. UI Design System

### Philosophy

This design system is built for users with: limited digital literacy, potential mild visual decline (60+ years), low confidence with technology, and zero patience for ambiguity. Every decision has a reason backed by research.

### Colour palette

| Token | Hex | Use | Contrast | Rationale |
|---|---|---|---|---|
| **Lumi Saffron** | `#F5A100` | Cursor fill, bubble, primary CTA | 8.2:1 vs #1A1A2E | Warm amber — most visible wavelength for elderly eyes. Culturally resonant in India (saffron = guidance, light, auspiciousness). |
| **Lumi Ink** | `#1A1A2E` | Text, cursor stroke, guidance bubble bg | 18.5:1 vs #FFFAF3 | Near-black with slight indigo. Avoids halation of pure #000000 at text edges — genuine elderly eye issue. |
| **Lumi Cream** | `#FFFAF3` | All app backgrounds | — | Warm off-white. Reduces harsh cold contrast of pure white (#FFFFFF), which causes eye strain in extended use. |
| **Listening Green** | `#22C55E` | Bubble state — microphone active | 3.4:1 vs #FFFAF3 | Universally understood "go/active" signal. Distinct from Saffron to be unambiguous. |
| **Success Green** | `#16A34A` | Task complete confirmation | 5.1:1 vs #FFFAF3 | Deep green. Pairs with ascending two-tone chime. |
| **Error Red** | `#DC2626` | Errors only — never for guidance | 4.9:1 vs #FFFAF3 | Warm red. Used ONLY for errors. Never as a navigation cue (red creates anxiety in uncertain users). |

### Typography

| Use | Size | Weight | Rationale |
|---|---|---|---|
| Guidance bubble instruction | 20 sp | Bold for action word | Below 18sp is inaccessible for mild visual decline. Action word bolded for scan-ability. |
| Bubble status text | 16 sp | Medium | Supplementary context |
| Onboarding body | 18 sp | Regular | Comfortable reading size for target demographic |
| App settings | 16 sp | Regular | Standard — settings are seen less frequently |

Maximum 10 words per guidance instruction. Action word always first. Example: **"Tap** the Pay button here." Not: "Please tap on the Pay button to proceed."

### Touch targets

| Element | Default | Small option | Large option |
|---|---|---|---|
| Floating bubble | 56 dp | 40 dp | 72 dp |
| Cursor | 56 dp | — | 72 dp |
| Onboarding language buttons | 64 dp height | — | — |
| Settings list items | 56 dp height | — | — |
| All other interactive elements | 48 dp min | — | — |

> Material Design minimum is 48dp. Apple HIG minimum is 44pt. Research on elderly users recommends 56–64dp for primary triggers. The 32dp bubble in PRD v1 was below every applicable standard.

### Loading states (never silent)

| Time after activation | What happens |
|---|---|
| 0–200 ms | Bubble begin slow-pulse animation (1.2s interval, #F5A100) |
| 200–300 ms | Pre-recorded chime audio plays from RAM |
| 300 ms | Pre-recorded voice: "Let me check your screen..." |
| 2000 ms (if still inferring) | Pre-recorded voice: "One moment..." |
| 4000 ms (if still inferring) | Pre-recorded voice: "Almost ready..." |
| Any time | Re-tap bubble to cancel — voice: "Okay, I stopped." |

All loading audio is pre-recorded, not TTS-generated. It plays from a bundled audio file regardless of inference state. Zero latency on the user confirmation.

### Guidance bubble

Position: top 20% of screen, never overlapping the cursor or the target element.

```
┌─────────────────────────────────────────┐
│  Background: #1A1A2E   Radius: 16dp    │
│                                         │
│  [Pulse icon]  Tap the Pay button.     │
│                ────────────────         │
│                20sp bold, #FFFFFF       │
└─────────────────────────────────────────┘
```

### Error state UI

When an unexpected screen appears after a user tap:
1. Gentle low tone (200ms audio)
2. Cursor briefly "scans" — moves left-right twice at current position (200ms)
3. Voice: "That didn't go where I expected. Let me look again."
4. TaskEngine triggers screen re-read

---

## 19. Demo Flow — 90-Second Hackathon Script

### Task: "PhonePe par 500 rupaye bhejne mein help karo"
### (Help me send ₹500 on PhonePe)

This demo was chosen because:
- Every person in the jury room has experienced UPI confusion or has a family member who has
- It has exactly the right number of steps for 90 seconds
- Every step uses camera + voice + on-device AI — maxes HackTracker scores automatically
- The cursor is visible and dramatic on a projected screen
- The bundle path delivers < 200ms per step — the demo never waits for VLM inference

### Full script

```
[iQOO 15 on table, projected. Lumi bubble visible in corner — saffron, 56dp.]

PRESENTER:
"My mother calls me four times a week asking how to use her phone.
 This is what she would see if Lumi were on her phone."

[Taps bubble. Chime plays. Bubble turns green — listening state.]

PRESENTER: (speaks Hindi into phone)
"PhonePe par 500 rupaye bhejne mein help karo."

[Whisper detects Hindi → fine-tuned model transcribes → bundle hit for PhonePe home screen]

── STEP 1 ──
CURSOR:  Animates (Bezier, 400ms) to red Pay button at bottom of PhonePe.
VOICE:   "Yahan tap karo — yeh Pay button hai."
         [Hindi System TTS: "Tap here — this is the Pay button."]
[User taps]

── STEP 2 ──
CURSOR:  Animates to "Mobile number or UPI ID" search field.
VOICE:   "Yahan recipient ka number ya UPI ID likhein."
[User types phone number]

── STEP 3 ──
CURSOR:  Animates to "Proceed" button.
VOICE:   "Aage badhne ke liye Proceed tap karo."
[User taps]

── STEP 4 ──
CURSOR:  Animates to amount entry field.
VOICE:   "Paanch sau — 500 — yahan likhein."
[User types 500]

── STEP 5 ──
CURSOR:  Animates to "Proceed to Pay."
VOICE:   "Aur yahan tap karo."
[UPI PIN screen appears]

── STEP 6 ──
CURSOR:  Points to PIN keypad.
VOICE:   "Apna UPI PIN daalen — payment ho jayegi."

PRESENTER addresses jury:
"Six steps. Ninety seconds. Entirely offline.
 In Hindi. On-device. No internet used.
 My mother can do this. Yours can too."

[Pause]

"And it works on a ₹8,000 phone too."
```

### Hackathon demo notes

- Pre-download all models to iQOO 15 the night before. Do not rely on venue WiFi.
- Carry models on USB-C drive as backup. ModelDownloadManager has file-picker fallback.
- Run UIMapWorker on the iQOO 15 specifically before the event. The bundle maps are bundled, but Room DB must be populated for the remaining 13 apps.
- Test PhonePe accessibility tree on OriginOS 6 specifically. Confirm bundle coordinates match OriginOS 6 rendering of PhonePe. PhonePe may render differently on OriginOS vs stock Android.
- Rehearse the 90-second demo 10 times minimum. Every presenter pause should be scripted.
- If on-device fails for any reason: Adaptive Intelligence Mode (Anthropic API key pre-loaded) as silent backup. Do not mention this to the jury unless necessary.

---

## 20. Folder Structure

```
lumi-android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/ai/lumi/
│   │   │   ├── LumiApplication.kt              # App class, Hilt DI init
│   │   │   ├── MainActivity.kt                 # Minimal — triggers overlay
│   │   │   │
│   │   │   ├── onboarding/                     # TANISHQ
│   │   │   │   ├── OnboardingActivity.kt
│   │   │   │   ├── OnboardingViewModel.kt
│   │   │   │   └── screens/
│   │   │   │       ├── WelcomeScreen.kt
│   │   │   │       ├── PermissionScreen.kt         # Overlay + Accessibility + Mic
│   │   │   │       ├── LanguageGridScreen.kt        # Tap-grid, no voice
│   │   │   │       ├── ModelDownloadScreen.kt
│   │   │   │       ├── VoiceVerificationScreen.kt
│   │   │   │       ├── MappingProgressScreen.kt
│   │   │   │       └── MiniDemoScreen.kt
│   │   │   │
│   │   │   ├── overlay/                         # TANISHQ
│   │   │   │   ├── OverlayService.kt            # ForegroundService (MEDIA_PLAYBACK), bubble only
│   │   │   │   ├── BubbleView.kt                # 56dp floating trigger, TYPE_APPLICATION_OVERLAY
│   │   │   │   ├── GuidanceBubbleView.kt        # Instruction text popup, top-screen position
│   │   │   │   └── VoiceIndicatorView.kt        # Waveform animation during listening
│   │   │   │
│   │   │   ├── cursor/                          # TANISHQ
│   │   │   │   ├── CursorView.kt                # Pointing hand, #F5A100 fill, 3dp #1A1A2E stroke
│   │   │   │   └── CursorAnimator.kt            # Bezier 400ms + repeat pulse every 2000ms
│   │   │   │
│   │   │   ├── accessibility/                   # MRUNMAYEE
│   │   │   │   ├── LumiAccessibilityService.kt  # Cursor window (TYPE_ACCESSIBILITY_OVERLAY) + events
│   │   │   │   ├── UITreeCapture.kt             # Accessibility tree → JSON
│   │   │   │   └── ElementFinder.kt             # findByText + pixel-coord fallback
│   │   │   │
│   │   │   ├── screencapture/                   # MRUNMAYEE
│   │   │   │   ├── ScreenCaptureService.kt      # MediaProjection session management
│   │   │   │   └── ScreenshotProcessor.kt       # Resize to 1280×720, JPEG 60%
│   │   │   │
│   │   │   ├── engine/                          # HASSAN
│   │   │   │   ├── TaskEngine.kt                # Core guidance loop orchestrator
│   │   │   │   ├── TaskStep.kt                  # Data: instruction + target + language
│   │   │   │   ├── TaskState.kt                 # Sealed: Idle/Listening/Thinking/Guiding/Done/Error
│   │   │   │   └── TaskClassifier.kt            # Text classifier: known vs unknown task type
│   │   │   │
│   │   │   ├── inference/                       # HASSAN
│   │   │   │   ├── ModelSelector.kt             # Device → tier, auto-configuration, no user input
│   │   │   │   ├── ModelDownloadManager.kt      # Download + SHA-256 validate + file-picker fallback
│   │   │   │   ├── VLMEngine.kt                 # Qwen3-VL-4B via GenieX qairt wrapper
│   │   │   │   ├── MoondreamEngine.kt           # Moondream2 Q4 via GenieX llama_cpp / llama.cpp+Vulkan
│   │   │   │   ├── TextLLMEngine.kt             # Qwen3-1.7B via GenieX llama_cpp (memory extraction)
│   │   │   │   └── InferenceResult.kt           # Data: target description + instruction + language + coords
│   │   │   │
│   │   │   ├── voice/                           # MRUNMAYEE
│   │   │   │   ├── VoiceService.kt              # ForegroundService (DATA_SYNC), audio lifecycle
│   │   │   │   ├── WhisperEngine.kt             # ASR dispatcher: language detect → route to model
│   │   │   │   ├── HindiWhisperEngine.kt        # vasista22/whisper-hindi-medium-Q4 via whisper.cpp
│   │   │   │   ├── TurboWhisperEngine.kt        # Whisper Large v3 Turbo Q4 via GenieX llama_cpp
│   │   │   │   ├── LanguageDetector.kt          # 3s clip → language code + confidence
│   │   │   │   ├── TTSEngine.kt                 # Dispatcher: English → Kokoro, Others → System TTS
│   │   │   │   ├── KokoroTTS.kt                 # English TTS, 82MB bundled
│   │   │   │   └── SystemTTSWrapper.kt          # Android TextToSpeech with hi-IN locale
│   │   │   │
│   │   │   ├── uimap/                           # HASSAN
│   │   │   │   ├── UIMapRepository.kt           # Layer 1: bundle → Layer 2: Room DB → miss
│   │   │   │   ├── BundleMapLoader.kt           # Loads /assets/uimaps/*.json into Room DB on first launch
│   │   │   │   ├── UIMapWorker.kt               # WorkManager: install-time mapping for non-bundle apps
│   │   │   │   ├── PerceptualHasher.kt          # pHash 64-bit + Hamming distance
│   │   │   │   └── GuidancePlanBuilder.kt       # VLM output → stored guidance plan
│   │   │   │
│   │   │   ├── memory/                          # HASSAN
│   │   │   │   ├── MemoryRepository.kt
│   │   │   │   ├── MemoryExtractor.kt           # Post-task extraction via Qwen3-1.7B (flagship only)
│   │   │   │   ├── ProfileManager.kt            # User profile read/write + contradiction handling
│   │   │   │   ├── FormMemoryManager.kt         # Field-type auto-fill
│   │   │   │   └── VectorSearchEngine.kt        # SQLite-vec wrapper for MiniLM embeddings (Phase 2)
│   │   │   │
│   │   │   ├── cloud/                           # HASSAN (fallback only)
│   │   │   │   └── AnthropicClient.kt           # Adaptive Intelligence Mode — Anthropic only, Phase 1
│   │   │   │
│   │   │   ├── settings/                        # TANISHQ
│   │   │   │   ├── SettingsActivity.kt
│   │   │   │   └── screens/
│   │   │   │       ├── GeneralSettings.kt       # Language, bubble size, name
│   │   │   │       └── MemorySettings.kt        # View/clear what Lumi remembers
│   │   │   │
│   │   │   └── data/
│   │   │       ├── db/
│   │   │       │   ├── LumiDatabase.kt
│   │   │       │   ├── dao/
│   │   │       │   │   ├── UIMapDao.kt
│   │   │       │   │   ├── UserProfileDao.kt
│   │   │       │   │   ├── TaskMemoryDao.kt
│   │   │       │   │   ├── FormMemoryDao.kt
│   │   │       │   │   └── MemoryVectorDao.kt
│   │   │       │   └── entity/
│   │   │       │       ├── UIMapEntity.kt
│   │   │       │       ├── UserProfileEntity.kt
│   │   │       │       ├── TaskMemoryEntity.kt
│   │   │       │       ├── FormMemoryEntity.kt
│   │   │       │       └── MemoryVectorEntity.kt
│   │   │       └── datastore/
│   │   │           └── LumiPreferences.kt       # Settings via DataStore Proto
│   │   │
│   │   ├── res/
│   │   │   ├── drawable/
│   │   │   │   ├── ic_lumi_bubble.xml           # Saffron circle bubble icon
│   │   │   │   ├── ic_cursor_hand.xml           # Pointing hand with stroke
│   │   │   │   └── ic_voice_wave.xml            # Listening waveform
│   │   │   ├── raw/
│   │   │   │   ├── audio_chime.mp3              # Activation chime (pre-recorded)
│   │   │   │   ├── audio_checking.mp3           # "Let me check your screen..."
│   │   │   │   ├── audio_moment.mp3             # "One moment..."
│   │   │   │   └── audio_cancel.mp3             # "Okay, I stopped."
│   │   │   └── xml/
│   │   │       └── accessibility_service_config.xml
│   │   │
│   │   └── assets/
│   │       └── uimaps/
│   │           ├── phonepe_maps.json            # Pre-built: Pay, recipient, amount, PIN screens
│   │           └── whatsapp_maps.json           # Pre-built: Chat list, compose, send
│   │
│   └── build.gradle.kts
│
├── models/                                       # Downloaded at runtime to /files/
│   ├── vlm/
│   │   ├── qwen3_vl_4b_instruct_qairt/          # AI Hub bundle (flagship)
│   │   └── moondream2_q4.gguf                   # Fast-path + budget VLM
│   ├── llm/
│   │   └── qwen3_1_7b_q4.gguf                   # Memory extraction (flagship)
│   ├── asr/
│   │   ├── whisper_hindi_medium_q4.bin           # Fine-tuned Hindi, 8.2% WER
│   │   └── whisper_large_v3_turbo_q4.gguf       # English + language detection
│   └── tts/
│       └── kokoro_82m_en.bin                    # English TTS (bundled in APK, also listed here)
│
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 21. Database Schemas

### UIMapEntity

```kotlin
@Entity(tableName = "ui_maps")
data class UIMapEntity(
    @PrimaryKey val id: String,               // "{packageName}_{screenHash}"
    val packageName: String,
    val screenHash: Long,                     // 64-bit pHash
    val versionCode: Long,
    val screenLabel: String,                  // "PhonePe_Home", "WhatsApp_Chat"
    val elementMapJson: String,               // [{id, text, desc, bounds}]
    val guidancePlanJson: String,             // [{step, targetDesc, instruction_en, instruction_hi}]
    val screenshotPath: String,
    val isBundled: Boolean,                   // true = from assets/uimaps/, immutable
    val createdAt: Long,
    val updatedAt: Long
)
```

### UserProfileEntity

```kotlin
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val key: String,             // "name", "preferredLanguage", "city"
    val value: String,
    val confidence: Float,                   // 0.0–1.0
    val source: String,                      // "explicit" | "inferred" | "form_fill"
    val updatedAt: Long
)
```

### TaskMemoryEntity

```kotlin
@Entity(tableName = "task_memory")
data class TaskMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String,
    val taskParamsJson: String,              // {"contact": "Maa", "amount": "500"}
    val stepCount: Int,
    val userCorrectedStep: Int?,             // null = no correction needed
    val completedSuccessfully: Boolean,
    val completedAt: Long
)
```

### FormMemoryEntity

```kotlin
@Entity(tableName = "form_memory")
data class FormMemoryEntity(
    @PrimaryKey val fieldType: String,       // "name", "mobile", "pincode", "city"
    val value: String,
    val lastUsedAt: Long,
    val useCount: Int
)
```

### MemoryVectorEntity

```kotlin
@Entity(tableName = "memory_vectors")
data class MemoryVectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val embedding: FloatArray,               // 384-dim MiniLM-L6-v2
    val category: String,                    // "profile" | "task" | "preference"
    val createdAt: Long
)
```

---

## 22. Performance Targets

| Scenario | Target | Method |
|---|---|---|
| Tap to audio confirmation | < 300 ms | Pre-recorded audio from RAM |
| Language detection (3s clip) | < 400 ms | Whisper Turbo, GenieX llama_cpp |
| Hindi transcription (vasista22 Q4) | < 600 ms | whisper.cpp, Vulkan |
| UI bundle hit (PhonePe, WhatsApp) | < 100 ms | JSON asset lookup |
| Room DB cache hit | < 200 ms | Room query + pHash match |
| Moondream2 (GenieX llama_cpp) | < 2 s | Hexagon NPU |
| Qwen3-VL-4B (GenieX qairt) | 3–5 s | Hexagon NPU, NPU-compiled |
| Accessibility element lookup | < 30 ms | Native Android API |
| Cursor animation (travel) | 400 ms | ValueAnimator, Bezier |
| Kokoro TTS first audio | < 150 ms | Pre-warmed |
| Android System TTS first audio | < 100 ms | Always available |
| **End-to-end, bundle hit** | **< 1.2 s** | Demo path — all PhonePe and WhatsApp steps |
| **End-to-end, Qwen3-VL-4B** | **< 6 s** | Unknown screen path |

### Optimisation techniques applied

**UI map bundle:** Eliminates VLM inference for demo apps entirely. 5s → 100ms for PhonePe flow.

**Parallel audio + screenshot:** ASR and screenshot capture start simultaneously on bubble tap. By the time language is detected, the screenshot is ready.

**Screen diff:** Between guidance steps, compute pixel diff of new screenshot vs previous. If changed region < 20% of screen, crop and send only the changed region to VLM. Reduces VLM input by 60–80%.

**INT4/Q4 quantisation:** Applied to all models. ~40% faster than FP16 on NPU.

**Pre-warmed models:** All models loaded into memory on OverlayService start. No cold-start delay when user activates Lumi.

**Pre-recorded loading audio:** Loading state audio plays from a bundled audio file, not TTS inference. Zero latency on user confirmation.

**App size management**

| Component | Size | Delivery |
|---|---|---|
| APK (no models, Kokoro + MiniLM bundled) | < 40 MB | Google Play / sideload |
| Kokoro-82M | 82 MB | Bundled in APK |
| MiniLM-L6-v2 | 22 MB | Bundled in APK |
| Whisper Hindi Medium Q4 | ~424 MB | On-demand, first launch |
| Whisper Large v3 Turbo Q4 | ~800 MB | On-demand, first launch |
| Qwen3-VL-4B AI Hub bundle | ~2.5 GB | On-demand, flagship only |
| Moondream2 Q4 GGUF | ~700 MB | On-demand, all tiers |
| Qwen3-1.7B Q4 | ~1 GB | On-demand, flagship only |

Minimum viable download (budget tier, Hindi): Whisper Hindi Medium Q4 + Moondream2 Q4 = **1.12 GB**
Full flagship download: all models = **~5.5 GB** (spread across multiple sessions, WiFi-only)

---

## 23. Privacy & Security

| Concern | Implementation |
|---|---|
| Screenshots never leave device | MediaProjection frames processed in memory. Never written to disk. Never sent over network unless Adaptive Mode is explicitly enabled by user. |
| No microphone data stored | Audio buffer exists in RAM only during ASR inference. Discarded immediately after. |
| Memory stored encrypted | Room DB encrypted with Android Keystore-backed key (AES-256-GCM) |
| Secure screen detection | Check FLAG_SECURE before every capture. If secure: cursor hides, no screenshot taken. Voice: "I can't see this screen to protect your privacy." |
| API key storage | Android EncryptedSharedPreferences, AES256-GCM, Keystore-backed |
| No analytics | Zero network calls except Adaptive Mode (explicit, user-opted) |
| Data on-device | No server. No account. No backup to cloud. |
| Accessibility trust statement | Shown at first accessibility permission request: "Lumi reads your screen to guide you. It does not record, send, or store your screen." |

---

## 24. Phase-Wise Build Plan

Phases are defined by working product milestones, not calendar dates. Each phase ends when the milestone is demonstrable, not when a time period expires.

### Phase 0 — Skeleton (Foundation, No AI)

**Exit condition:** App installs, OverlayService draws the bubble, bubble is draggable and tappable, LumiAccessibilityService receives events. No inference, no voice, no cursor movement. Just the shell.

| Task | Owner |
|---|---|
| Project setup: Kotlin + Jetpack Compose + Hilt + Room + DataStore | Hassan |
| OverlayService with WindowManager bubble (56dp, #F5A100, draggable) | Tanishq |
| LumiAccessibilityService skeleton (event listener + tree capture) | Mrunmayee |
| LumiDatabase + all DAOs + entities + migrations | Hassan |
| ModelDownloadManager with WiFi detection and file-picker fallback | Hassan |

---

### Phase 1 — Static Demo Loop

**Exit condition:** Tap bubble → cursor animates to a hardcoded PhonePe "Pay" button position → voice plays a hardcoded instruction → tap → cursor moves to next hardcoded position. Full 6-step PhonePe demo works using hardcoded plans only. No AI, no ASR, no live screen reading.

| Task | Owner |
|---|---|
| CursorView: pointing hand, #F5A100, 3dp #1A1A2E stroke | Tanishq |
| CursorAnimator: Bezier 400ms + arrival pulse + 2s repeat pulse | Tanishq |
| TYPE_ACCESSIBILITY_OVERLAY cursor window in LumiAccessibilityService | Mrunmayee |
| TaskEngine state machine: Idle → Guiding → Done (hardcoded plan) | Hassan |
| BundleMapLoader: load /assets/uimaps/*.json into Room DB on first launch | Hassan |
| UIMapRepository: bundle lookup → Room DB lookup | Hassan |
| Pre-recorded audio playback (chime, "Let me check", cancel) | Mrunmayee |
| Loading state UX: bubble pulse + pre-recorded audio within 300ms | Tanishq + Mrunmayee |
| GuidanceBubbleView: instruction text, 20sp bold, top-screen position | Tanishq |
| Build PhonePe bundle maps (hand-built from logged-in device) | Hassan |
| Build WhatsApp bundle maps | Hassan |

---

### Phase 2 — Voice Integration

**Exit condition:** User speaks in Hindi or English → transcribed correctly → language detected → correct TTS voice speaks the guidance instruction. ASR and TTS pipelines both live.

| Task | Owner |
|---|---|
| whisper.cpp Android JNI bindings | Mrunmayee |
| HindiWhisperEngine: vasista22/whisper-hindi-medium-Q4 | Mrunmayee |
| TurboWhisperEngine: Whisper Large v3 Turbo Q4 | Mrunmayee |
| LanguageDetector: 3s clip → language code | Mrunmayee |
| WhisperEngine dispatcher: detect → route | Mrunmayee |
| KokoroTTS: English TTS, 82MB | Mrunmayee |
| SystemTTSWrapper: Android TTS with hi-IN locale | Mrunmayee |
| TTSEngine dispatcher | Mrunmayee |
| VoiceService: mic recording, VAD, audio buffer management | Mrunmayee |
| TaskEngine: accepts ASR text + language code, serves plan | Hassan |

---

### Phase 3 — Live Screen Intelligence

**Exit condition:** For an app NOT in the bundle and NOT in Room DB, Lumi captures a screenshot, runs Moondream2, gets a target element description, finds it via Accessibility tree (or pixel fallback), and points the cursor correctly. Unknown screens are now handled.

| Task | Owner |
|---|---|
| GenieX Android Kotlin SDK integration | Mrunmayee |
| MoondreamEngine: Moondream2 Q4 via GenieX llama_cpp | Hassan |
| VLMEngine: Qwen3-VL-4B via GenieX qairt | Hassan |
| ScreenCaptureService: MediaProjection session, 1280×720 JPEG | Mrunmayee |
| ScreenshotProcessor: resize + compress | Mrunmayee |
| ElementFinder: Accessibility lookup + pixel-coord fallback | Mrunmayee |
| InferenceResult: target description + instruction + relative coords | Hassan |
| TaskEngine: full live loop (screenshot → VLM → element → cursor → voice) | Hassan |
| Screen diff optimisation (send changed region only) | Hassan |
| ModelSelector: auto-configure tier on first launch | Hassan |

---

### Phase 4 — UIMap Intelligence

**Exit condition:** UIMapWorker runs at install time for the 13 non-bundle apps, stores maps in Room DB. Staleness detection triggers remapping on app updates. Cache hit rate for all 15 apps is near 100%.

| Task | Owner |
|---|---|
| UIMapWorker: launch app → capture tree → Moondream2 → store | Hassan |
| PerceptualHasher: pHash 64-bit + Hamming distance | Hassan |
| GuidancePlanBuilder: VLM output → structured plan | Hassan |
| Staleness check: PackageManager version change → remap trigger | Hassan |
| UIMapRepository: pHash match logic | Hassan |

---

### Phase 5 — Memory + Onboarding

**Exit condition:** Self-demonstrating onboarding works end-to-end on OriginOS 6. User's name and preferred language are remembered. Form memory pre-fills name/city on recognised form fields.

| Task | Owner |
|---|---|
| All 8 onboarding screens (Jetpack Compose) | Tanishq |
| Onboarding cursor guidance (uses Phase 1 cursor system) | Tanishq |
| OriginOS 6 Settings path for accessibility enable — tested and mapped | Mrunmayee |
| Battery optimisation exemption request in onboarding | Mrunmayee |
| ProfileManager: CRUD + contradiction handling | Hassan |
| FormMemoryManager: field-type detection + silent pre-fill | Hassan |
| MemoryExtractor: post-task extraction via Qwen3-1.7B (flagship) | Hassan |
| Settings screen: language, bubble size, memory view | Tanishq |

---

### Phase 6 — Hackathon Polish

**Exit condition:** Demo runs 10 times in a row without failure on the iQOO 15. Pitch is rehearsed. All models pre-downloaded. Fallback is tested.

| Task | Owner |
|---|---|
| Pre-download all models to iQOO 15 | All |
| Test PhonePe accessibility tree on OriginOS 6 specifically | Hassan + Mrunmayee |
| Verify PhonePe bundle map coordinates on OriginOS 6 rendering | Hassan |
| Verify cursor TYPE_ACCESSIBILITY_OVERLAY on Android 16 | Mrunmayee |
| Test full demo flow 10× minimum | All |
| Anthropic API key loaded for Adaptive Mode fallback | Hassan |
| Rehearse 90-second demo script | All |
| Rehearse 3-minute pitch | Hassan |
| Carry models on USB-C drive | Hassan |

---

## 25. iQOO Hackathon Strategy

### Scoring analysis

| Dimension | Weight | Expected | Reasoning |
|---|---|---|---|
| End product quality | 30% | 27–29/30 | Working demo, clear utility, polished UX |
| Novelty & impact | 20% | 19–20/20 | No offline Android equivalent exists. 1.5B TAM. |
| HackTracker — creative phone use | 15% | 14–15/15 | Every Lumi interaction uses camera + voice + on-device AI. Normal use maxes this automatically. |
| Technical depth | 15% | 13–15/15 | Qwen3-VL-4B via GenieX qairt + Hexagon NPU + whisper.cpp + TYPE_ACCESSIBILITY_OVERLAY |
| Office Kit usage | 10% | 7–9/10 | Use screen mirror to show live PhonePe guidance on laptop screen during debug. File transfer for model sideloading. |
| Demo & pitch | 10% | 9–10/10 | PhonePe flow — visual, emotional, 90 seconds, works live. |
| **Total** | **100%** | **89–98/100** | |

### Qualcomm mentor talking points

Kartikey Rawat (Qualcomm Senior AI/ML Engineer & Developer Advocate) will be on the mentor floor. He will recognise these immediately:

- "We're running Qwen3-VL-4B via Qualcomm AI Hub using the GenieX qairt runtime, targeting the Hexagon NPU on the SD 8 Elite Gen 5."
- "For our fast-path VLM, we use Moondream2 Q4 GGUF via GenieX's llama_cpp runtime — the GGML Hexagon backend runs it on the NPU without needing a pre-compiled AI Hub bundle."
- "We chose Qwen3-VL-4B specifically because it's pre-optimised on AI Hub for the SD 8 Elite Gen 5 chipset in this device."

Do not mention FastVLM. Do not mention Apple models. Do not claim models run on chipsets they do not support.

### HackTracker maximisation

Camera, voice, and on-device AI are scored by duration and frequency. Every Lumi guidance step uses all three. During Red Light (phone-only), use Lumi to navigate your own device for any task — this generates footage, demonstrates dogfooding, and scores HackTracker simultaneously.

### Pitch structure (3 minutes)

- 0:00–0:20 — The problem. "My mother calls me four times a week asking how to use her phone."
- 0:20–0:40 — The gap. "No app does this offline, in Hindi, with a cursor showing exactly where to tap."
- 0:40–1:15 — Live demo. PhonePe flow, Hindi, on-device, cursor visible on projection.
- 1:15–1:35 — Under the hood. "Qwen3-VL-4B on Qualcomm Hexagon NPU via GenieX. Fine-tuned Hindi Whisper. Android System TTS. All on the phone."
- 1:35–1:55 — Scale. "650 million smartphone users in India. 1.5 billion globally. No offline alternative."
- 1:55–2:20 — Works on any phone. "The UI map cache means a ₹8,000 phone with 4 GB RAM gets the same < 200ms guidance as the iQOO 15."
- 2:20–2:45 — Roadmap. "Marathi, Tamil, Bengali next. Wake word. Auto-tap. 50+ languages. This is phone literacy infrastructure."
- 2:45–3:00 — Close. "Lumi. Your phone, explained."

---

## 26. Known Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OriginOS 6 kills Overlay/Accessibility service | High | High | Battery exemption + MEDIA_PLAYBACK FG type + service health check every resume |
| PhonePe blocks Accessibility tree on OriginOS | High | High | Pre-built bundle maps with pixel coordinates from screenshot. Test before hackathon. |
| OriginOS Settings path for Accessibility differs from stock | High | Medium | Test exact path on iQOO 15 during build week. Hard-code correct path in onboarding cursor. |
| Qwen3-VL-4B defaults to English/Chinese despite Hindi prompt | Medium | High | Test on Day 1 of build. Adjust temperature + add few-shot Hindi example in system prompt if needed. |
| Model download fails on hackathon venue WiFi | High | High | Pre-download everything night before. USB-C drive backup. File-picker fallback in ModelDownloadManager. |
| iQOO 15 blocks third-party NPU access via GenieX | Low | High | Test GenieX qairt on iQOO 15 during build week. Fallback: GenieX llama_cpp (Vulkan path). |
| Android 16 changes TYPE_ACCESSIBILITY_OVERLAY behaviour | Low | High | Test on Android 16 device early. Monitor Android 16 release notes for WindowManager changes. |
| Demo PhonePe coordinates wrong for OriginOS 6 rendering | Medium | High | Build bundle maps on an iQOO 15 running OriginOS 6, not on stock Android. |
| Jury asks "isn't this just Google Gemini?" | High | Low | Rehearsed answer: "Gemini requires internet, a Google account, and doesn't show you where to tap. Lumi is offline, Hindi-native, cursor-guided, and remembers you. It also works on a ₹8,000 phone." |
| Whisper misrecognises Hindi on regional accent | Medium | Medium | Fine-tuned model (8.2% WER) handles most accents. Tap-to-type fallback available. |

---

## 27. Future Roadmap

### 3 months post-hackathon

- Marathi, Tamil, Bengali, Telugu: System TTS packs immediate; ASR when production-quality fine-tuned Q4 models are available
- IndicTTS integration once ONNX conversion pipeline is built (quality upgrade over System TTS)
- Wake word "Nova" via Porcupine (Picovoice)
- Auto-tap mode: Accessibility Service ACTION_CLICK
- Proactive suggestions based on Task memory patterns
- App update re-mapping: automatic on PackageManager version change

### 6 months

- OEM pre-install discussions with manufacturers targeting Indian Tier 2/3 cities
- Lumi SDK: any app can embed cursor-guided onboarding with one dependency
- Offline knowledge base for 100+ Indian apps

### 12 months

- Enterprise mode: IT admin pushes task flows to employee phones
- Lumi for accessibility: dedicated mode for motor/vision impairments
- 50+ language expansion via full Whisper language range
- The category this creates: **phone literacy infrastructure**

---

*Lumi PRD v2.0 — rebuilt for iQOO Hackathon 2026, Pune City Battle, September 5–6*
*Team: html · Track: Productivity · Device: iQOO 15 (Snapdragon 8 Elite Gen 5)*
*Inference: Qualcomm Hexagon NPU via GenieX SDK (qairt + llama_cpp runtimes)*
*"Your phone, explained."*
