package ai.lumi.engine

import android.graphics.Rect

/**
 * Sealed hierarchy representing the TaskEngine's current state.
 * Drives the OverlayService UI and VoiceService behaviour.
 */
sealed class TaskState {
    /** Lumi is idle — bubble visible, nothing active. */
    data object Idle : TaskState()

    /** Bubble tapped, pre-recorded chime playing, screenshot being taken. */
    data object Initializing : TaskState()

    /** Microphone open, waiting for user to speak. */
    data object Listening : TaskState()

    /** ASR transcribed, VLM or bundle lookup in progress. */
    data class Thinking(val transcribedText: String, val language: String) : TaskState()

    /** Cursor visible, animating to or pulsing at the target element. */
    data class Guiding(val currentStep: TaskStep, val stepIndex: Int, val totalSteps: Int) : TaskState()

    /** Unexpected screen after a tap — re-reading the screen. */
    data object Recovering : TaskState()

    /** All steps complete. */
    data class Done(val language: String) : TaskState()

    /** Unrecoverable error — user shown error state. */
    data class Error(val message: String, val language: String) : TaskState()
}

/**
 * A single step in a guidance plan.
 *
 * @param targetDescription   Semantic description used for Accessibility tree lookup
 * @param instructionEn       Fallback English instruction (≤10 words)
 * @param instructionNative   Instruction in the user's detected language (may equal instructionEn)
 * @param relativeX           VLM pixel fallback X [0.0, 1.0]
 * @param relativeY           VLM pixel fallback Y [0.0, 1.0]
 * @param resolvedBounds      Set by TaskEngine after Accessibility lookup
 * @param isLastStep          True for the final step in the plan
 */
data class TaskStep(
    val stepIndex: Int,
    val targetDescription: String,
    val instructionEn: String,
    val instructionNative: String,   // language-native instruction (Hindi, Tamil, etc.)
    val targetId: Int? = null,
    val relativeX: Float = 0f,
    val relativeY: Float = 0f,
    val resolvedBounds: Rect? = null,
    val isLastStep: Boolean = false,
    val wait: Boolean = false,
    val nativeLanguage: String = "hi",
    val labelHints: List<String> = emptyList(),
    val resourceIdHints: List<String> = emptyList(),
    val action: String = "CLICK"
) {
    /** Returns the instruction in [language]. If matching [nativeLanguage], uses the
     *  native translation. Falls back to English if blank or for any other language. */
    fun instruction(language: String): String =
        if (language == nativeLanguage) instructionNative.ifBlank { instructionEn } else instructionEn
}
