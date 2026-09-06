package ai.lumi.engine

import javax.inject.Inject
import javax.inject.Singleton

enum class TapOutcome {
    CORRECT,
    WRONG_ELEMENT,
    NO_CHANGE
}

/**
 * Evaluates the outcome of a user tap (PRD Section 3.5).
 * Compares screen perceptual hashes before and after tap to detect whether
 * the user tapped the correct target, tapped something else, or had no effect.
 */
@Singleton
class TapEvaluator @Inject constructor() {

    companion object {
        const val DEFAULT_CHANGE_THRESHOLD = 8   // bits difference to confirm screen updated
        const val DEFAULT_MATCH_THRESHOLD = 12   // bits distance to confirm match with expected screen
        const val MAX_RETRIES_BEFORE_AUDIO_ESCALATION = 3
    }

    fun evaluateTap(
        preHash: Long,
        postHash: Long,
        expectedNextScreenHash: Long? = null,
        changeThreshold: Int = DEFAULT_CHANGE_THRESHOLD,
        matchThreshold: Int = DEFAULT_MATCH_THRESHOLD
    ): TapOutcome {
        val diff = hammingDistance(preHash, postHash)
        val screenChanged = diff > changeThreshold

        if (!screenChanged) {
            return TapOutcome.NO_CHANGE
        }

        if (expectedNextScreenHash != null) {
            val distToExpected = hammingDistance(postHash, expectedNextScreenHash)
            return if (distToExpected < matchThreshold) {
                TapOutcome.CORRECT
            } else {
                TapOutcome.WRONG_ELEMENT
            }
        }

        // When no ground-truth expected hash is available, a detected UI transition is considered CORRECT
        return TapOutcome.CORRECT
    }

    fun hammingDistance(h1: Long, h2: Long): Int {
        return java.lang.Long.bitCount(h1 xor h2)
    }

    fun getCorrectionTts(outcome: TapOutcome, language: String): String? {
        return when (outcome) {
            TapOutcome.WRONG_ELEMENT -> if (language == "hi") "Woh nahi — yeh wala tap karo" else "Not that one — tap this instead"
            TapOutcome.NO_CHANGE -> if (language == "hi") "Dobara try karo, yahan tap karo" else "Try again, tap here"
            TapOutcome.CORRECT -> null
        }
    }
}
