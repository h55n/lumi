package ai.lumi.inference

/**
 * Result from any VLM inference call.
 *
 * @param targetDescription  Semantic description of the UI element to tap (e.g. "Pay button")
 * @param instruction        Short instruction in the user's language (max 10 words)
 * @param language           BCP-47 language code ("hi", "en", etc.)
 * @param relativeX          Normalised X coordinate [0.0, 1.0] from VLM screenshot output
 * @param relativeY          Normalised Y coordinate [0.0, 1.0] from VLM screenshot output
 * @param targetId           The ID of the UI element to tap (assigned during UITree JSON generation)
 * @param confidence         Model confidence [0.0, 1.0]
 * @param status             Status of the goal ("in_progress", "complete", "stuck")
 * @param errorMessage       Non-null when the model signalled an error state
 */
data class InferenceResult(
    val targetDescription: String,
    val instruction: String,
    val language: String,
    val targetId: Int? = null,
    val relativeX: Float = 0f,
    val relativeY: Float = 0f,
    val confidence: Float = 1f,
    val status: String = "in_progress",
    val wait: Boolean = false,
    val errorMessage: String? = null
) {
    val isError: Boolean get() = errorMessage != null

    val isTaskComplete: Boolean get() = status == "complete"
    val isStuck: Boolean get() = status == "stuck"

    companion object {
        fun error(message: String, language: String = "en") = InferenceResult(
            targetDescription = "",
            instruction = if (language == "hi") "Kuch galat hua" else "Something went wrong",
            language = language,
            errorMessage = message
        )

        fun taskComplete(language: String = "en") = InferenceResult(
            targetDescription = "",
            instruction = if (language == "hi") "Kaam ho gaya!" else "Done!",
            language = language,
            status = "complete"
        )
    }
}
