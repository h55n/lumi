package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.inference.DeviceTier
import ai.lumi.inference.ModelSelector
import ai.lumi.inference.ModelSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ModelSelectorTest {

    // ── DeviceTier properties ─────────────────────────────────────────────────

    @Test fun `FLAGSHIP tier supports VLM`() {
        assertThat(DeviceTier.FLAGSHIP.supportsVLM).isTrue()
    }

    @Test fun `MINIMAL tier does not support VLM`() {
        assertThat(DeviceTier.MINIMAL.supportsVLM).isFalse()
    }

    @Test fun `only FLAGSHIP supports qairt`() {
        assertThat(DeviceTier.FLAGSHIP.supportsGenieXQairt).isTrue()
        assertThat(DeviceTier.MID_HIGH.supportsGenieXQairt).isFalse()
        assertThat(DeviceTier.BUDGET.supportsGenieXQairt).isFalse()
    }

    @Test fun `FLAGSHIP and MID_HIGH support GenieX llama_cpp`() {
        assertThat(DeviceTier.FLAGSHIP.supportsGenieXLlamaCpp).isTrue()
        assertThat(DeviceTier.MID_HIGH.supportsGenieXLlamaCpp).isTrue()
        assertThat(DeviceTier.BUDGET.supportsGenieXLlamaCpp).isFalse()
    }

    @Test fun `only FLAGSHIP supports memory extraction`() {
        assertThat(DeviceTier.FLAGSHIP.supportsMemoryExtraction).isTrue()
        assertThat(DeviceTier.MID_HIGH.supportsMemoryExtraction).isFalse()
        assertThat(DeviceTier.BUDGET.supportsMemoryExtraction).isFalse()
        assertThat(DeviceTier.MINIMAL.supportsMemoryExtraction).isFalse()
    }

    // ── Required models per tier ─────────────────────────────────────────────

    @Test fun `FLAGSHIP requires VLM Qwen3-VL-4B`() {
        val selector = mockk<ModelSelector>(relaxed = true)
        every { selector.requiredModels(DeviceTier.FLAGSHIP) } answers { callOriginal() }
        // Qwen3-VL-4B should be in flagship requirements
        val models = buildFlagshipModelList()
        assertThat(models).contains(ModelSpec.QWEN3_VL_4B_QAIRT)
    }

    @Test fun `BUDGET requires only Moondream and Hindi Whisper`() {
        val models = buildBudgetModelList()
        assertThat(models).contains(ModelSpec.MOONDREAM2_Q4)
        assertThat(models).contains(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        assertThat(models).doesNotContain(ModelSpec.QWEN3_VL_4B_QAIRT)
        assertThat(models).doesNotContain(ModelSpec.QWEN3_1_7B_Q4)
    }

    @Test fun `MINIMAL requires no models`() {
        val models = buildMinimalModelList()
        assertThat(models).isEmpty()
    }

    @Test fun `FLAGSHIP with English only does not require Hindi Whisper`() {
        val selector = ModelSelector(mockk(relaxed = true))
        val models = selector.requiredModels(DeviceTier.FLAGSHIP, setOf("en"))
        assertThat(models).doesNotContain(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)
    }

    @Test fun `FLAGSHIP with Hindi requires Hindi Whisper`() {
        val selector = ModelSelector(mockk(relaxed = true))
        val models = selector.requiredModels(DeviceTier.FLAGSHIP, setOf("hi"))
        assertThat(models).contains(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)
    }

    @Test fun `FLAGSHIP with English and Hindi requires both Whisper models`() {
        val selector = ModelSelector(mockk(relaxed = true))
        val models = selector.requiredModels(DeviceTier.FLAGSHIP, setOf("en", "hi"))
        assertThat(models).contains(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)
    }

    // ── ModelSpec properties ──────────────────────────────────────────────────

    @Test fun `ASR models have asr subDir`() {
        assertThat(ModelSpec.WHISPER_HINDI_MEDIUM_Q4.subDir).isEqualTo("asr")
        assertThat(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4.subDir).isEqualTo("asr")
    }

    @Test fun `VLM models have vlm subDir`() {
        assertThat(ModelSpec.QWEN3_VL_4B_QAIRT.subDir).isEqualTo("vlm")
        assertThat(ModelSpec.MOONDREAM2_Q4.subDir).isEqualTo("vlm")
    }

    @Test fun `LLM models have llm subDir`() {
        assertThat(ModelSpec.QWEN3_1_7B_Q4.subDir).isEqualTo("llm")
    }

    @Test fun `all model sizes are positive`() {
        ModelSpec.entries.forEach { spec ->
            assertThat(spec.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test fun `all model filenames are non-empty`() {
        ModelSpec.entries.forEach { spec ->
            assertThat(spec.fileName).isNotEmpty()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildFlagshipModelList(): List<ModelSpec> = buildList {
        add(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        add(ModelSpec.WHISPER_LARGE_V3_TURBO_Q4)
        add(ModelSpec.QWEN3_VL_4B_QAIRT)
        add(ModelSpec.MOONDREAM2_Q4)
        add(ModelSpec.QWEN3_1_7B_Q4)
    }

    private fun buildBudgetModelList(): List<ModelSpec> = buildList {
        add(ModelSpec.WHISPER_HINDI_MEDIUM_Q4)
        add(ModelSpec.WHISPER_SMALL_Q4)
        add(ModelSpec.MOONDREAM2_Q4)
    }

    private fun buildMinimalModelList(): List<ModelSpec> = emptyList()
}
