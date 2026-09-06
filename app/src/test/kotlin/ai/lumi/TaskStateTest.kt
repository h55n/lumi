package ai.lumi

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import ai.lumi.accessibility.ElementFinder
import ai.lumi.engine.TaskState
import ai.lumi.engine.TaskStep
import org.junit.Test

class TaskStateTest {

    @Test fun `Idle is not Guiding`() {
        val state: TaskState = TaskState.Idle
        assertThat(state is TaskState.Guiding).isFalse()
    }

    @Test fun `Guiding holds step data`() {
        val step = TaskStep(0, "Pay button", "Tap Pay", "Pay tap karo", isLastStep = false)
        val state = TaskState.Guiding(step, 0, 6)
        assertThat(state.currentStep.targetDescription).isEqualTo("Pay button")
        assertThat(state.stepIndex).isEqualTo(0)
        assertThat(state.totalSteps).isEqualTo(6)
    }

    @Test fun `Error state holds message and language`() {
        val state = TaskState.Error("model failed", "hi")
        assertThat(state.message).isEqualTo("model failed")
        assertThat(state.language).isEqualTo("hi")
    }

    @Test fun `Done state holds language`() {
        val state = TaskState.Done("en")
        assertThat(state.language).isEqualTo("en")
    }

    @Test fun `Thinking state holds transcribed text and language`() {
        val state = TaskState.Thinking("500 bhejo", "hi")
        assertThat(state.transcribedText).isEqualTo("500 bhejo")
        assertThat(state.language).isEqualTo("hi")
    }
}

class TaskStepTest {

    @Test fun `instruction returns English for unknown language`() {
        val step = TaskStep(0, "x", "EN instruction", "HI instruction", isLastStep = false)
        assertThat(step.instruction("ta")).isEqualTo("EN instruction") // Tamil not in step, falls back
    }

    @Test fun `instruction returns Hindi text for hi code`() {
        val step = TaskStep(0, "x", "EN instruction", "HI instruction", isLastStep = false)
        assertThat(step.instruction("hi")).isEqualTo("HI instruction")
    }

    @Test fun `instruction returns English text for en code`() {
        val step = TaskStep(0, "x", "EN instruction", "HI instruction", isLastStep = false)
        assertThat(step.instruction("en")).isEqualTo("EN instruction")
    }

    @Test fun `resolved bounds is null by default`() {
        val step = TaskStep(0, "x", "y", "z", isLastStep = false)
        assertThat(step.resolvedBounds).isNull()
    }
}
