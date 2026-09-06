# Lumi — Product Requirements Document
### Version 4.0 · Post-Judgment Session · September 6, 2026
### Team: html · Track: Productivity · iQOO Hackathon 2026, Pune

> **What changed from v3 → v4:** Complete problem audit + architectural rebuild. Every failure mode from v3 is addressed with a named fix, owner file, and acceptance criterion. See Section 6 for the full delta log.

---

## 1. Problem Registry — Every Failure, Named

This section lists every problem identified across both the post-v3 judgment and the additional issues raised during the build session. Each problem has a root cause, a severity, and a pointer to its solution section.

### P-01 — Cursor points to vague / wrong elements
**Severity:** Critical (demo-breaking)
**Root cause:** VLM received the full accessibility tree but used training priors to pick coordinates — not the actual on-screen bounds. Coordinates returned as 0.0–1.0 ratios were then mapped incorrectly after screen density scaling.
**Additional cause:** No guard existed to confirm the target element was actually present before the cursor was placed.
**Solution:** Section 3.1 — Deterministic Element Matcher

### P-02 — Screenshots consume device memory
**Severity:** High
**Root cause:** Full 1080p screenshots (~3MB bitmaps) were captured and held in memory during inference. No explicit `Bitmap.recycle()` was called. GC was relied upon for cleanup, which is non-deterministic on Android.
**Solution:** Section 3.3 — Memory-Safe Screenshot Pipeline

### P-03 — Lumi interferes with foreground app performance
**Severity:** High
**Root cause:** Overlay windows, AccessibilityService polling, and in-process VLM inference all competed for the same CPU/GPU/NPU budget as the foreground app. No process priority or memory class boundary was enforced.
**Solution:** Section 3.4 — Non-Interference Architecture

### P-04 — Image processing too slow / VLM latency unacceptable
**Severity:** Critical (demo-breaking)
**Root cause:** VLM was the primary guidance engine, not a fallback. On-device Qwen3-VL-4B takes 3–5s. Cloud VLM adds network RTT on top of inference. No deterministic fast path existed for known flows.
**Solution:** Section 3.1 + Section 3.2 — Match-First Architecture

### P-05 — No ability to evaluate if user did the right or wrong action
**Severity:** High (correctness-breaking)
**Root cause:** v3 relied entirely on AccessibilityEvents to advance steps. Events fire on every tap regardless of correctness. There was no mechanism to compare the resulting screen state against the expected outcome.
**Solution:** Section 3.5 — Post-Tap Action Evaluator

### P-06 — Cannot interpret screen correctly via image recognition
**Severity:** High
**Root cause:** VLM prompt lacked grounding. The model was asked "where is the Pay button?" without being shown a structured list of what was actually visible. It answered from memory of what PhonePe looks like in training data.
**Solution:** Section 3.1 + Section 3.6 — Grounded VLM Prompting (when VLM fires at all)

### P-07 — No correction when user taps wrong element
**Severity:** High
**Root cause:** v3 had no correction branch. If a user tapped incorrectly, the system either advanced to a nonsensical next step or stalled silently. No voice correction line existed.
**Solution:** Section 3.5 — Three-Outcome Evaluator with Correction TTS

### P-08 — VLM as primary path is fragile for demo
**Severity:** Critical
**Root cause:** The demo flow (PhonePe send money) was entirely dependent on a bundle cache hit. Any cache miss triggered the 3–5s VLM path. Demo failure on cache miss was guaranteed under real conditions.
**Solution:** Section 3.2 — Action Template Engine + Section 3.1 — Semantic Matcher

### P-09 — FLAG_SECURE / fintech app strategy is brittle
**Severity:** Medium
**Root cause:** The fallback for `FLAG_SECURE + tree blocked` was hardcoded pixel coordinates. These break on any screen density change, font scale change, or accessibility setting.
**Solution:** Section 3.2 — Template Engine with Label Hints (coordinate-free)

### P-10 — False-positive cursor loops (phantom re-evaluation)
**Severity:** Medium
**Root cause:** `onTargetLost()` fired even when the element was never confirmed present, causing the guidance engine to re-evaluate indefinitely.
**Solution:** Section 3.7 — targetWasFound Guard (kept from v3) + early exit on zero-confidence score

### P-11 — No VLM call timeout / no guaranteed fallback
**Severity:** High
**Root cause:** If VLM (on-device or cloud) stalled, the guidance engine waited indefinitely. No hard timeout existed. User experienced a frozen UI.
**Solution:** Section 3.6 — Hard 600ms VLM Timeout with Audio-Only Fallback

### P-12 — Duplicate guidance bubble from two sources
**Severity:** Low (fixed in v3, must not regress)
**Root cause:** Both OverlayService and LumiAccessibilityService were each creating a guidance bubble window.
**Solution:** Section 3.8 — Single-Source Overlay Contract (regression guard)

---

## 2. Architecture Overview — v4 "Match First, Infer Never"

### Core principle

> VLM is the last resort. Deterministic accessibility matching is the primary engine. Semantic heuristics are the bridge. Screenshot is only taken post-tap for verification — never for cursor placement.

### Full signal pipeline

```
User taps bubble
    ↓
[Phase 1] Intent Extraction                         <330ms total
    Whisper Q4 on NPU → raw transcript
    Intent Classifier (ONNX) → {action_type, entity, lang, app_hint}
    ↓
[Phase 2] Deterministic Element Matching            <80ms
    Walk AccessibilityNodeInfo tree
    Score every node: resourceId + text + contentDesc + class + clickable
    Best score > threshold → FOUND
    ↓ miss
    Action Template Engine                          <120ms
    Package + intent_type → load JSON recipe
    Per-step: resourceIdHints → labelHints (Hindi + English)
    Resolve against live tree → exact pixel bounds
    ↓ miss
[Phase 3] Cropped VLM (last resort, <5% of steps)  <600ms hard timeout
    Capture 320×320px quad around likely zone
    Moondream2 Q4 on NPU: "where is [entity] in this image?"
    Returns bounding box → mapped to physical coords
    Timeout → Audio-Only fallback
    ↓
[Phase 4] Cursor Placement                         0ms (bounds already known)
    Cursor drawn at exact node bounds center
    400ms Bezier animation
    TTS speaks instruction in user's language
    ↓
User taps
    ↓
[Phase 5] Post-Tap Screenshot Verification         <200ms
    Capture 320×320px quad — single reusable buffer
    pHash compare: expected next screen vs actual
    THREE OUTCOMES:
      CORRECT  → advance step, update history, move cursor
      WRONG    → re-point cursor, TTS correction, retry_count++
      NO CHANGE → pulse cursor, TTS repeat with emphasis
    Bitmap.recycle() immediately
    ↓
Loop until task complete or max retries reached
```

---

## 3. Solution Specifications

### 3.1 — Deterministic Element Matcher

**Solves:** P-01, P-04, P-06, P-08

The semantic element matcher is the primary guidance engine. It never calls a model. It runs entirely in-process on the AccessibilityService thread.

**Scoring algorithm:**

```kotlin
data class ParsedIntent(
    val actionType: String,
    val entityLabels: List<String>,      // e.g. ["Pay", "पे", "पेमेंट"]
    val entityResourceIds: List<String>, // e.g. ["pay_button", "btn_pay"]
    val expectedClass: String?           // e.g. "android.widget.Button"
)

fun scoreNode(node: AccessibilityNodeInfo, intent: ParsedIntent): Float {
    var score = 0f
    val text = listOfNotNull(
        node.text?.toString(),
        node.contentDescription?.toString(),
        node.hintText?.toString()
    ).joinToString(" ").lowercase().trim()
    val resourceId = node.viewIdResourceName?.substringAfterLast("/") ?: ""

    // Tier 1: exact resource ID match (highest confidence)
    if (intent.entityResourceIds.any { resourceId.contains(it, ignoreCase = true) }) score += 1.0f

    // Tier 2: label match in any supported language
    if (intent.entityLabels.any { text.contains(it.lowercase()) }) score += 0.8f

    // Tier 3: element is interactive
    if (node.isClickable || node.isFocusable) score += 0.2f

    // Tier 4: class matches expected (e.g., Button vs TextView)
    if (intent.expectedClass != null && node.className == intent.expectedClass) score += 0.3f

    // Penalty: not visible or not enabled
    if (!node.isVisibleToUser || !node.isEnabled) score = 0f

    return score
}
```

**Acceptance criteria:**
- Must return result in <80ms on Snapdragon 8 Elite Gen 5
- Must handle null `text`, `contentDescription`, `viewIdResourceName` gracefully
- Must score zero for invisible or disabled nodes
- Must return the node's `getBoundsInScreen()` — never a ratio coordinate

---

### 3.2 — Action Template Engine

**Solves:** P-08, P-09, P-01

Templates are JSON files bundled in APK assets. Each template maps a `(package, intent_type)` pair to an ordered list of steps. Steps use label hints, not pixel coordinates — they are resolved against the live tree at runtime.

**Template schema:**

```json
{
  "package": "com.phonepe.app",
  "intent": "send_money",
  "version_min": 0,
  "steps": [
    {
      "step_id": "tap_pay",
      "resourceIdHints": ["pay_button", "btn_pay", "home_pay"],
      "labelHints": ["Pay", "पे", "पेमेंट", "Send"],
      "expectedClass": "android.widget.Button",
      "action": "CLICK",
      "tts_hi": "Yahan tap karo — yeh Pay button hai",
      "tts_en": "Tap here — this is the Pay button"
    },
    {
      "step_id": "enter_recipient",
      "resourceIdHints": ["et_mobile", "input_mobile", "phone_number"],
      "labelHints": ["number", "नंबर", "mobile", "recipient"],
      "expectedClass": "android.widget.EditText",
      "action": "TYPE_ENTITY",
      "tts_hi": "Recipient ka number likhein",
      "tts_en": "Enter the recipient's number"
    },
    {
      "step_id": "tap_proceed",
      "resourceIdHints": ["btn_proceed", "proceed"],
      "labelHints": ["Proceed", "आगे", "Next", "Continue"],
      "action": "CLICK",
      "tts_hi": "Proceed tap karo",
      "tts_en": "Tap Proceed"
    },
    {
      "step_id": "enter_amount",
      "resourceIdHints": ["et_amount", "amount_input", "txt_amount"],
      "labelHints": ["amount", "राशि", "₹", "Enter amount"],
      "action": "TYPE_AMOUNT",
      "tts_hi": "500 yahan likhein",
      "tts_en": "Enter 500 here"
    },
    {
      "step_id": "tap_proceed_pay",
      "resourceIdHints": ["btn_proceed_pay", "proceed_to_pay"],
      "labelHints": ["Proceed to Pay", "Pay Now", "भेजें"],
      "action": "CLICK",
      "tts_hi": "Yahan tap karo",
      "tts_en": "Tap here"
    },
    {
      "step_id": "pin_screen",
      "secure": true,
      "action": "AUDIO_ONLY",
      "tts_hi": "Apna UPI PIN daalen",
      "tts_en": "Enter your UPI PIN"
    }
  ]
}
```

**Bundled templates (v4 launch set):**

| Package | Intent | Steps | FLAG_SECURE handled |
|---|---|---|---|
| `com.phonepe.app` | `send_money` | 6 | Yes (step 6) |
| `com.whatsapp` | `send_message` | 5 | No |
| `com.android.contacts` | `make_call` | 4 | No |
| `com.android.settings` | `toggle_wifi` | 3 | No |
| `com.google.android.camera` | `take_photo` | 2 | No |

**Resolution logic:** For each step, try resourceIdHints first (O(1) lookup), then labelHints (O(n) node walk). If both fail, fall through to Phase 3 VLM only for that step.

**Acceptance criteria:**
- Template miss must not crash — must fall through to Element Matcher then VLM
- Label hints must be matched case-insensitively in both Hindi and English
- `secure: true` steps must never attempt screenshot or tree capture
- Template resolution must complete in <120ms

---

### 3.3 — Memory-Safe Screenshot Pipeline

**Solves:** P-02

**Rules:**

1. Screenshots are only taken post-tap for verification (Phase 5). Never for cursor placement.
2. Capture size is fixed at 320×320px cropped to the region of interest — not full screen.
3. A single `Bitmap` buffer is allocated at service start and reused across all captures.
4. `Bitmap.recycle()` is called immediately after pHash computation. GC is not relied upon.
5. Screenshots are never written to storage, never passed to any clipboard, never transmitted unless the user has explicitly opted into Adaptive Mode.

**Implementation:**

```kotlin
object ScreenshotBuffer {
    private val lock = ReentrantLock()
    private var buffer: Bitmap? = null

    fun initialize() {
        buffer = Bitmap.createBitmap(320, 320, Bitmap.Config.RGB_565)
    }

    fun captureQuadrant(
        projection: MediaProjection,
        regionOfInterest: Rect
    ): Long { // returns pHash, not Bitmap
        lock.withLock {
            val bmp = buffer ?: return 0L
            // draw into buffer — reuse allocation
            val canvas = Canvas(bmp)
            // ... capture + crop logic
            val hash = PHashComputer.compute(bmp)
            // explicit recycle — do not leave to GC
            canvas.setBitmap(null)
            return hash
        }
    }

    fun destroy() {
        buffer?.recycle()
        buffer = null
    }
}
```

**Memory footprint:**
- Buffer size: 320 × 320 × 2 bytes (RGB_565) = ~200KB static allocation
- pHash result: 8 bytes (Long)
- Net memory held after Phase 5 completes: 8 bytes
- Previous v3 worst case: 3MB per step, held until GC

**Acceptance criteria:**
- `buffer` must never be null when `captureQuadrant` is called (assert in debug builds)
- `Bitmap.recycle()` must be called before `captureQuadrant` returns
- No screenshot Bitmap reference must escape `ScreenshotBuffer`
- FLAG_SECURE frames (near-zero entropy pHash) must be detected and routed to tree-only verification without crashing

---

### 3.4 — Non-Interference Architecture

**Solves:** P-03

Lumi must never degrade the performance of the app the user is being guided through. This is enforced at three levels.

**Level 1 — Process separation**

```
Lumi process                        Foreground app process
─────────────────────               ─────────────────────
LumiAccessibilityService            PhonePe / WhatsApp / etc.
OverlayService                      (separate PID, separate heap)
ScreenshotBuffer (200KB)
Intent Classifier (ONNX, CPU)
Moondream2 (NPU — shared, scheduled)
```

Android's process model guarantees separate heaps. Lumi's memory pressure cannot directly cause the foreground app to be killed. Lumi's process is in `PROCESS_STATE_FOREGROUND_SERVICE` — it will be killed before any foreground app if memory is critically low.

**Level 2 — Overlay non-interception**

```kotlin
overlayParams.flags = (
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE        // cursor never eats taps
    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE     // never steals keyboard focus
    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
)
overlayParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
```

The cursor overlay never intercepts any touch event. The user always taps the actual app element.

**Level 3 — NPU scheduling**

Moondream2 runs on the NPU via GenieX. The NPU scheduler on Snapdragon 8 Elite Gen 5 gives priority to the foreground app's rendering pipeline (GPU) and then to the foreground app's NPU requests. Lumi's NPU inference runs in `NNAPI_PRIORITY_LOW` — it will be pre-empted by the foreground app's own ML inference.

```kotlin
nnApiDelegate = NnApiDelegate(NnApiDelegate.Options().apply {
    executionPriority = NnApiDelegate.Options.EXECUTION_PRIORITY_LOW
})
```

**Acceptance criteria:**
- Frame rate of foreground app must not drop >5fps during Lumi guidance (measured with `adb shell dumpsys gfxinfo`)
- Lumi must not hold any Wake Lock during guidance
- Lumi must release MediaProjection immediately after each Phase 5 capture

---

### 3.5 — Post-Tap Action Evaluator (Three-Outcome)

**Solves:** P-05, P-07

This is the core new system in v4. After every user tap, Lumi evaluates whether the correct action was taken before advancing.

**Evaluation flow:**

```kotlin
enum class TapOutcome { CORRECT, WRONG_ELEMENT, NO_CHANGE }

fun evaluateTap(
    preHash: Long,
    postHash: Long,
    expectedNextScreenHash: Long?,
    accessibilityEvent: AccessibilityEvent
): TapOutcome {
    val changed = hammingDistance(preHash, postHash) > CHANGE_THRESHOLD // e.g. 8 bits
    if (!changed) return TapOutcome.NO_CHANGE

    if (expectedNextScreenHash != null) {
        val matchesExpected = hammingDistance(postHash, expectedNextScreenHash) < MATCH_THRESHOLD // e.g. 12 bits
        return if (matchesExpected) TapOutcome.CORRECT else TapOutcome.WRONG_ELEMENT
    }

    // No expected hash available (non-template step) — changed = probably correct
    return TapOutcome.CORRECT
}
```

**Response matrix:**

| Outcome | Cursor | TTS (Hindi) | TTS (English) | State update |
|---|---|---|---|---|
| `CORRECT` | Moves to next target | "Sahi kiya! Ab yahan tap karo…" | "Good, now tap here…" | advance step, reset retry_count |
| `WRONG_ELEMENT` | Re-points to correct target | "Woh nahi — yeh wala tap karo" | "Not that one — tap this instead" | retry_count++ |
| `NO_CHANGE` | Pulses in place | "Dobara try karo, yahan tap karo" | "Try again, tap here" | retry_count++ |

**Escalation:** After `retry_count >= 3` on the same step, cursor hides entirely and TTS delivers a full verbal walkthrough of the step without any cursor guidance.

**Expected next screen hash:** Populated from the template (if available) or from the previous VLM inference that returned a predicted post-action state hash. Falls back to change-detection-only if neither is available.

**Acceptance criteria:**
- `evaluateTap` must fire within 200ms of `AccessibilityEvent`
- Hamming distance constants must be tunable per-app via template metadata
- `retry_count` must reset to 0 on successful step advance
- Audio-only escalation must fire before `retry_count` reaches 4

---

### 3.6 — Grounded VLM Prompting + Hard Timeout

**Solves:** P-04, P-06, P-11

VLM is Phase 3 — it fires only when Phase 1 (Element Matcher) and Phase 2 (Template Engine) both fail. When it does fire, the prompt is grounded and the call has a hard timeout.

**Input:** Not the full screenshot. A single 320×320px crop of the screen quadrant most likely to contain the target element (estimated from the intent entity type and step position in the task).

**Prompt spec:**

```
You are analyzing a 320x320px crop of a phone screen.
Foreground app: {packageName}
User's goal: {entityLabel} ({actionType})
Completed steps so far: {stepHistory.last3}

Your task: find the bounding box of "{entityLabel}" in this image.

RULES:
- Return ONLY a JSON object: {"x1": int, "y1": int, "x2": int, "y2": int, "confidence": float}
- Coordinates are in pixels within this 320x320 image
- If you cannot find the element with confidence > 0.6, return {"found": false}
- DO NOT reference elements from other apps or your training data
- DO NOT guess — only return elements you can see in this image
```

**Result mapping:**
- VLM returns bounding box in 320px crop space → map to physical screen coords using crop origin offset
- `confidence < 0.6` or `found: false` → audio-only fallback, no cursor shown

**Timeout:**

```kotlin
val result = withTimeoutOrNull(600L) {
    moondream.infer(croppedBitmap, prompt)
}
if (result == null) {
    // timeout — fall to audio-only immediately
    tts.speak(currentStep.ttsForLanguage(lang), flush = true)
    return
}
```

**Acceptance criteria:**
- VLM must never be called for steps that have a template match
- Crop must be `Bitmap.Config.RGB_565` — same reusable buffer as Phase 5
- 600ms hard timeout must be enforced via `withTimeoutOrNull` — no blocking call
- Audio-only TTS must fire within 50ms of timeout

---

### 3.7 — targetWasFound Guard (Regression Prevention)

**Solves:** P-10

Kept from v3 and strengthened. The cursor is never shown unless the target element has been confirmed present in the current window.

```kotlin
var targetWasFound: Boolean = false

fun onWindowChanged(event: AccessibilityEvent) {
    val score = elementMatcher.score(currentTarget, event.source)
    if (score > FOUND_THRESHOLD) {
        targetWasFound = true
        showCursorAt(event.source.getBoundsInScreen())
    } else if (targetWasFound) {
        // Was found, now gone → screen changed → advance
        targetWasFound = false
        advanceToNextStep()
    } else {
        // Never found on this screen → navigation instruction
        hideCursor()
        tts.speak(currentStep.navigationInstruction)
    }
}
```

**Additional guard:** If Element Matcher returns score of exactly 0.0 for all nodes, no cursor is shown and the system speaks the navigation instruction. This prevents the cursor from appearing at `(0, 0)` or screen center on a complete miss.

**Acceptance criteria:**
- Cursor must never appear before `targetWasFound = true`
- `advanceToNextStep()` must only fire after `targetWasFound` was `true`
- Score of 0.0 must produce audio-only state, not cursor-at-origin

---

### 3.8 — Single-Source Overlay Contract

**Solves:** P-12 (regression guard)

Only `OverlayService` creates overlay windows. `LumiAccessibilityService` never creates any `WindowManager` window. This is enforced by architecture — `LumiAccessibilityService` has no reference to `WindowManager`.

```kotlin
// LumiAccessibilityService.kt
// NO WindowManager import here — enforced by package-private visibility

class LumiAccessibilityService : AccessibilityService() {
    // Communicates with OverlayService via LocalBroadcastManager only
    // Never creates any Window
}
```

**Acceptance criteria:**
- `grep -r "WindowManager" app/src/main/java/.../LumiAccessibilityService.kt` must return zero results
- Only one guidance bubble visible at any time — verified by Espresso overlay test

---

## 4. File Change Map — v4

| File | Change | Solves |
|---|---|---|
| `ElementMatcher.kt` | **New.** Scores AccessibilityNodeInfo tree against ParsedIntent. Returns node + bounds, never coordinates. | P-01, P-04, P-06 |
| `IntentClassifier.kt` | **New.** ONNX model wrapper. Input: Whisper transcript. Output: `ParsedIntent` struct. | P-01, P-06 |
| `ActionTemplateEngine.kt` | **New.** Loads JSON templates from assets. Resolves steps against live tree. | P-08, P-09 |
| `ScreenshotBuffer.kt` | **New.** Single reusable 320px bitmap. pHash output only. `recycle()` on every call. | P-02 |
| `TapEvaluator.kt` | **New.** pHash comparison. Returns `TapOutcome`. Drives correction TTS. | P-05, P-07 |
| `AnthropicClient.kt` / `MoondreamClient.kt` | **Changed.** Now receives 320px crop + grounded prompt. Hard 600ms timeout. Only called from Phase 3. | P-04, P-06, P-11 |
| `TaskEngine.kt` | **Changed.** Orchestrates 5-phase pipeline. Calls ElementMatcher → TemplateEngine → VLM in order. | P-08 |
| `LumiAccessibilityService.kt` | **Changed.** WindowManager removed. `targetWasFound` guard strengthened. No overlay creation. | P-03, P-10, P-12 |
| `OverlayService.kt` | **Changed.** Sole owner of all overlay windows. Receives cursor position from TaskEngine via broadcast. | P-03, P-12 |
| `CursorView.kt` | **Changed.** `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE` enforced. Bounds-based positioning only. | P-03, P-01 |
| `assets/templates/phonepe_send_money.json` | **New.** 6-step PhonePe send-money template with Hindi + English labels. | P-08, P-09 |
| `assets/templates/whatsapp_send_message.json` | **New.** 5-step WhatsApp send-message template. | P-08 |
| `assets/templates/contacts_make_call.json` | **New.** 4-step Contacts call template. | P-08 |
| `assets/templates/settings_wifi.json` | **New.** 3-step Settings WiFi toggle template. | P-08 |
| `UIMapRepository.kt` | **Kept.** MAP_TTL_DAYS=7, isAgeStale() unchanged from v3. | — |

---

## 5. Latency Budget — v4 Guarantees

| Path | P50 | P99 | Notes |
|---|---|---|---|
| ASR (Whisper Q4) | 280ms | 400ms | NPU, measured on iQOO 15 |
| Intent classifier | 15ms | 30ms | ONNX CPU, 4 threads |
| Element Matcher (tree hit) | 40ms | 80ms | O(n) node walk, ~150 nodes |
| Template Engine (template hit) | 60ms | 120ms | JSON load + tree resolve |
| Phase 5 screenshot + pHash | 80ms | 180ms | 320px capture + 64-bit hash |
| VLM (Moondream2 Q4, NPU) | 400ms | 600ms | Hard timeout at 600ms |
| **End-to-end, template hit** | **500ms** | **900ms** | ASR + classifier + template + cursor |
| **End-to-end, VLM fallback** | **1.1s** | **1.8s** | ASR + classifier + VLM |
| **v3 VLM primary path** | **3s** | **5s** | Previous baseline (failed) |

---

## 6. v3 → v4 Delta Log

| Dimension | v3 (failed) | v4 (fixed) | Problem solved |
|---|---|---|---|
| Primary guidance engine | VLM (probabilistic, 3–5s) | Element Matcher (deterministic, <80ms) | P-01, P-04 |
| Screenshot purpose | Cursor placement (full 1080p) | Post-tap verification only (320px crop) | P-02, P-04 |
| Screenshot memory | Held until GC (~3MB) | Recycled immediately (200KB buffer) | P-02 |
| Foreground app interference | NPU contention, no priority | NNAPI_PRIORITY_LOW, separate process | P-03 |
| Overlay touch interception | Not specified | FLAG_NOT_TOUCHABLE enforced | P-03 |
| VLM call rate | ~100% of steps | <5% of steps | P-04 |
| VLM prompt grounding | Goal string only | 320px crop + grounded entity prompt | P-06 |
| VLM timeout | None | 600ms hard timeout → audio-only | P-11 |
| Action verification | None | pHash three-outcome evaluator | P-05 |
| Wrong-tap correction | None | TTS correction + cursor re-points | P-07 |
| FLAG_SECURE fallback | Hardcoded pixel coords | Label-hint template (coordinate-free) | P-09 |
| False-positive cursor | `targetWasFound` guard (partial) | Guard + zero-score → audio-only | P-10 |
| Duplicate overlay | Fixed in v3 | Regression guard via architecture | P-12 |
| Hindi intent parsing | Whisper → raw string → VLM | Whisper → ONNX classifier → struct | P-01, P-06 |

---

## 7. Hackathon Demo Hardening — 90-Second Flow

**Task:** "PhonePe par 500 rupaye bhejne mein help karo"

**Expected path:** Template hit on every step. VLM never called.

| Step | Phase used | Latency | Fallback |
|---|---|---|---|
| "Pay" button | Template: `tap_pay` | <120ms | Element Matcher |
| Recipient field | Template: `enter_recipient` | <120ms | Element Matcher |
| "Proceed" | Template: `tap_proceed` | <120ms | Element Matcher |
| Amount field | Template: `enter_amount` | <120ms | Element Matcher |
| "Proceed to Pay" | Template: `tap_proceed_pay` | <120ms | Element Matcher |
| PIN screen | Template: `pin_screen` (audio-only) | <50ms | — |

**Cold-start protocol:** Clear app data → run demo → all 6 steps must complete via template. If any step falls to VLM, the template is broken — fix before demo day.

**Demo mode flag:** `BuildConfig.DEMO_MODE = true` pre-warms the template engine and suppresses the VLM path entirely. Use only for demo. Remove from production build.

---

## 8. Privacy and Security — Unchanged Commitments

| Concern | Implementation |
|---|---|
| Screenshots | In-memory only (320px buffer), never written to storage, recycled after pHash |
| Microphone | RAM only during Whisper inference, discarded after classifier |
| FLAG_SECURE | No screenshot taken (black frame detected → tree-only), cursor hides |
| UPI PIN | Cursor hides, no tree capture, no screenshot, audio-only |
| Room DB | Encrypted with Android Keystore AES-256-GCM |
| Network | Zero calls except Adaptive Mode (explicit user opt-in) |
| Overlay | FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE — never intercepts input |
| Process priority | PROCESS_STATE_FOREGROUND_SERVICE — killed before any foreground app |

---

## 9. Open Items Before Demo Day

| Item | Owner | Deadline |
|---|---|---|
| ONNX intent classifier training (Hindi/Hinglish/EN) | ML lead | Day 2 |
| PhonePe template — verify all 6 resourceIdHints on actual app | Android lead | Day 1 |
| WhatsApp template | Android lead | Day 3 |
| pHash threshold calibration (CHANGE_THRESHOLD, MATCH_THRESHOLD) | Android lead | Day 3 |
| `NNAPI_PRIORITY_LOW` integration test (frame rate impact) | QA | Day 3 |
| Demo mode flag + pre-warm tested on cold boot | All | Day 4 |
| Regression test: zero WindowManager refs in AccessibilityService | CI | Day 1 |

---

*Lumi PRD v4.0 — full problem registry + solution specifications*
*Team: html · Track: Productivity · Device: iQOO 15 (Snapdragon 8 Elite Gen 5)*
*"Your phone, explained."*
