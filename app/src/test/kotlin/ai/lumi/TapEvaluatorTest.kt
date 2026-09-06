package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.TapEvaluator
import ai.lumi.engine.TapOutcome
import org.junit.Before
import org.junit.Test

class TapEvaluatorTest {

    private lateinit var evaluator: TapEvaluator

    @Before
    fun setUp() {
        evaluator = TapEvaluator()
    }

    @Test
    fun `identical hashes return NO_CHANGE`() {
        val hash = 0x123456789ABCDEF0L
        val outcome = evaluator.evaluateTap(hash, hash)
        assertThat(outcome).isEqualTo(TapOutcome.NO_CHANGE)
    }

    @Test
    fun `changed screen with matching expected hash returns CORRECT`() {
        val pre = 0x0000000000000000L
        val post = 0x7FFFFFFF00000000L  // 31 bits diff from pre -> changed
        val expected = 0x7FFFFFFE00000000L // 1 bit diff from post -> matches expected (<12 bits)

        val outcome = evaluator.evaluateTap(pre, post, expected)
        assertThat(outcome).isEqualTo(TapOutcome.CORRECT)
    }

    @Test
    fun `changed screen with distant expected hash returns WRONG_ELEMENT`() {
        val pre = 0x0000000000000000L
        val post = 0x000000007FFFFFFFL   // 31 bits diff from pre -> changed
        val expected = 0x7FFFFFFF00000000L // 62 bits diff from post -> does not match expected

        val outcome = evaluator.evaluateTap(pre, post, expected)
        assertThat(outcome).isEqualTo(TapOutcome.WRONG_ELEMENT)
    }

    @Test
    fun `changed screen without expected hash returns CORRECT`() {
        val pre = 0x0000000000000000L
        val post = 0x000000007FFFFFFFL

        val outcome = evaluator.evaluateTap(pre, post, null)
        assertThat(outcome).isEqualTo(TapOutcome.CORRECT)
    }

    @Test
    fun `correction TTS returns expected localized messages`() {
        assertThat(evaluator.getCorrectionTts(TapOutcome.WRONG_ELEMENT, "hi"))
            .isEqualTo("Woh nahi — yeh wala tap karo")
        assertThat(evaluator.getCorrectionTts(TapOutcome.WRONG_ELEMENT, "en"))
            .isEqualTo("Not that one — tap this instead")

        assertThat(evaluator.getCorrectionTts(TapOutcome.NO_CHANGE, "hi"))
            .isEqualTo("Dobara try karo, yahan tap karo")
        assertThat(evaluator.getCorrectionTts(TapOutcome.NO_CHANGE, "en"))
            .isEqualTo("Try again, tap here")

        assertThat(evaluator.getCorrectionTts(TapOutcome.CORRECT, "hi")).isNull()
    }
}
