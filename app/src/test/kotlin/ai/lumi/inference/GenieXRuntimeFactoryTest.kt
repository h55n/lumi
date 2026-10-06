package ai.lumi.inference

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class GenieXRuntimeFactoryTest {
    @Test
    fun `VLM factory is unavailable instead of returning a canned session`() {
        val modelFile = File.createTempFile("lumi-vlm-test", ".model")
        try {
            DeviceTier.values().forEach { tier ->
                assertThat(GenieXRuntimeFactory.createVLMSession(tier, modelFile)).isNull()
            }
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun `text factory is unavailable instead of returning canned memory facts`() {
        val modelFile = File.createTempFile("lumi-text-test", ".model")
        try {
            assertThat(
                GenieXRuntimeFactory.createTextSession(DeviceTier.FLAGSHIP, modelFile)
            ).isNull()
        } finally {
            modelFile.delete()
        }
    }
}
