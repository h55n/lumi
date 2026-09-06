package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.TaskStep
import ai.lumi.inference.InferenceResult
import ai.lumi.uimap.GuidancePlanBuilder
import org.junit.Test

class InferenceResultTest {

    @Test fun `error factory sets isError true`() {
        val r = InferenceResult.error("oops", "en")
        assertThat(r.isError).isTrue()
        assertThat(r.errorMessage).isEqualTo("oops")
    }

    @Test fun `taskComplete factory sets isTaskComplete`() {
        val r = InferenceResult.taskComplete("en")
        assertThat(r.isTaskComplete).isTrue()
        assertThat(r.isError).isFalse()
    }

    @Test fun `Hindi task complete uses Hindi text`() {
        val r = InferenceResult.taskComplete("hi")
        assertThat(r.instruction).contains("gaya")
    }

    @Test fun `normal result is not error and not complete`() {
        val r = InferenceResult("Pay button", "Tap here", "en", relativeX = 0.5f, relativeY = 0.85f)
        assertThat(r.isError).isFalse()
        assertThat(r.isTaskComplete).isFalse()
    }

    @Test fun `default confidence is 1f for normal result`() {
        val r = InferenceResult("x", "y", "en")
        assertThat(r.confidence).isEqualTo(1f)
    }
}

class GuidancePlanBuilderTest {

    private val builder = GuidancePlanBuilder()

    @Test fun `round-trip serialization preserves all fields`() {
        val steps = listOf(
            TaskStep(0, "Pay button", "Tap Pay", "Tap karo", relativeX = 0.5f, relativeY = 0.88f, isLastStep = false),
            TaskStep(1, "Amount field", "Type 500", "500 likhein", relativeX = 0.5f, relativeY = 0.4f, isLastStep = true)
        )
        val json = builder.toJson(steps)
        val restored = builder.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.size).isEqualTo(2)
        assertThat(restored[0].targetDescription).isEqualTo("Pay button")
        assertThat(restored[0].instructionEn).isEqualTo("Tap Pay")
        assertThat(restored[0].instructionNative).isEqualTo("Tap karo")
        assertThat(restored[0].relativeX).isEqualTo(0.5f)
        assertThat(restored[0].relativeY).isEqualTo(0.88f)
        assertThat(restored[0].isLastStep).isFalse()
        assertThat(restored[1].isLastStep).isTrue()
    }

    @Test fun `fromJson with invalid JSON returns null`() {
        val result = builder.fromJson("not json at all")
        assertThat(result).isNull()
    }

    @Test fun `empty steps list round-trips`() {
        val json = builder.toJson(emptyList())
        val result = builder.fromJson(json)
        assertThat(result).isNotNull()
        assertThat(result!!).isEmpty()
    }

    @Test fun `TaskStep instruction returns correct language`() {
        val step = TaskStep(0, "Pay", "Tap Pay", "Pay tap karo", isLastStep = false)
        assertThat(step.instruction("en")).isEqualTo("Tap Pay")
        assertThat(step.instruction("hi")).isEqualTo("Pay tap karo")
        assertThat(step.instruction("mr")).isEqualTo("Tap Pay") // falls back to English
    }

    @Test fun `instruction falls back to English when Hindi is blank`() {
        val step = TaskStep(0, "x", "English instruction", "", isLastStep = false)
        assertThat(step.instruction("hi")).isEqualTo("English instruction")
    }
}
