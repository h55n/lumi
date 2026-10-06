package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.inference.DeviceTier
import ai.lumi.inference.ModelSelector
import ai.lumi.inference.ModelSpec
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

    @Test fun `FLAGSHIP selects supported local ASR and excludes unimplemented GenieX models`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.FLAGSHIP, setOf("en"))

        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0)
        assertThat(models).doesNotContain(ModelSpec.QWEN3_VL_4B_QAIRT)
        assertThat(models).doesNotContain(ModelSpec.MOONDREAM2_Q4_K)
        assertThat(models).doesNotContain(ModelSpec.QWEN3_1_7B_Q4_K_M)
    }

    @Test fun `BUDGET selects the verified Whisper Small artifact`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.BUDGET, setOf("en"))

        assertThat(models).containsExactly(ModelSpec.WHISPER_SMALL_Q5_1)
    }

    @Test fun `BUDGET with Hindi adds the multilingual medium model`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.BUDGET, setOf("hi"))

        assertThat(models).contains(ModelSpec.WHISPER_SMALL_Q5_1)
        assertThat(models).contains(ModelSpec.WHISPER_MEDIUM_Q5_0)
    }

    @Test fun `MINIMAL requires no models when no language is selected`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.MINIMAL)
        assertThat(models).isEmpty()
    }

    @Test fun `FLAGSHIP with English only does not require Hindi Whisper`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.FLAGSHIP, setOf("en"))
        assertThat(models).doesNotContain(ModelSpec.WHISPER_MEDIUM_Q5_0)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0)
    }

    @Test fun `FLAGSHIP with Hindi requires the multilingual medium model`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.FLAGSHIP, setOf("hi"))
        assertThat(models).contains(ModelSpec.WHISPER_MEDIUM_Q5_0)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0)
    }

    @Test fun `FLAGSHIP with English and Hindi requires both Whisper models`() {
        val models = ModelSelector(mockk(relaxed = true))
            .requiredModels(DeviceTier.FLAGSHIP, setOf("en", "hi"))
        assertThat(models).contains(ModelSpec.WHISPER_MEDIUM_Q5_0)
        assertThat(models).contains(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0)
    }

    // ── ModelSpec properties ──────────────────────────────────────────────────

    @Test fun `ASR models have asr subDir`() {
        assertThat(ModelSpec.WHISPER_MEDIUM_Q5_0.subDir).isEqualTo("asr")
        assertThat(ModelSpec.WHISPER_LARGE_V3_TURBO_Q5_0.subDir).isEqualTo("asr")
        assertThat(ModelSpec.WHISPER_SMALL_Q5_1.subDir).isEqualTo("asr")
    }

    @Test fun `VLM models have vlm subDir`() {
        assertThat(ModelSpec.QWEN3_VL_4B_QAIRT.subDir).isEqualTo("vlm")
        assertThat(ModelSpec.MOONDREAM2_Q4_K.subDir).isEqualTo("vlm")
    }

    @Test fun `LLM models have llm subDir`() {
        assertThat(ModelSpec.QWEN3_1_7B_Q4_K_M.subDir).isEqualTo("llm")
    }

    @Test fun `models without real GenieX adapters are not runtime integrated`() {
        assertThat(ModelSpec.MOONDREAM2_Q4_K.hasRuntimeIntegration).isFalse()
        assertThat(ModelSpec.QWEN3_1_7B_Q4_K_M.hasRuntimeIntegration).isFalse()
        assertThat(ModelSpec.QWEN3_VL_4B_QAIRT.hasRuntimeIntegration).isFalse()
    }

    @Test fun `all model sizes are positive`() {
        ModelSpec.entries.forEach { spec ->
            assertThat(spec.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test fun `direct artifacts have trusted checksums and immutable revisions`() {
        val downloadable = ModelSpec.entries.filter { it.isDirectDownloadable }
        assertThat(downloadable).isNotEmpty()
        downloadable.forEach { spec ->
            assertThat(spec.hasTrustedSha256).isTrue()
            assertThat(spec.sha256.length).isEqualTo(64)
            assertThat(spec.downloadUrl).contains("/resolve/")
        }
        assertThat(ModelSpec.QWEN3_VL_4B_QAIRT.hasTrustedSha256).isFalse()
    }

    @Test fun `all model filenames are non-empty`() {
        ModelSpec.entries.forEach { spec ->
            assertThat(spec.fileName).isNotEmpty()
        }
    }
}
