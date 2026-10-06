package ai.lumi.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fixture = "lumi-model-integrity-fixture"
    private val fixtureSha256 = "de304087d519078ba6a600a3b7450547dccd2c3189ddb3327bf58cd43efe0fd8"

    @Test
    fun `model validation requires an exact byte count and trusted SHA-256`() {
        val file = temporaryFolder.newFile("model.gguf").apply { writeText(fixture) }

        assertThat(ModelIntegrity.isValidSha256(fixtureSha256)).isTrue()
        assertThat(ModelIntegrity.isValidSha256("REPLACE_WITH_ACTUAL_SHA256")).isFalse()
        assertThat(ModelIntegrity.matches(file, fixture.toByteArray().size.toLong(), fixtureSha256)).isTrue()
        assertThat(ModelIntegrity.matches(file, fixture.toByteArray().size + 1L, fixtureSha256)).isFalse()
        assertThat(ModelIntegrity.matches(file, fixture.toByteArray().size.toLong(), "0".repeat(64))).isFalse()
    }

    @Test
    fun `invalid staged file cannot replace a previously installed model`() {
        val destination = temporaryFolder.newFile("installed.gguf").apply { writeText("known-good-model") }
        val staged = temporaryFolder.newFile("staged.part").apply { writeText("truncated-model") }

        val published = ModelIntegrity.publishVerifiedFile(
            stagedFile = staged,
            destination = destination,
            expectedBytes = fixture.toByteArray().size.toLong(),
            expectedSha256 = fixtureSha256
        )

        assertThat(published).isFalse()
        assertThat(destination.readText()).isEqualTo("known-good-model")
        assertThat(staged.exists()).isTrue()
    }

    @Test
    fun `verified staged file atomically replaces the installed model`() {
        val destination = temporaryFolder.newFile("installed.gguf").apply { writeText("old-model") }
        val staged = temporaryFolder.newFile("staged.part").apply { writeText(fixture) }

        val published = ModelIntegrity.publishVerifiedFile(
            stagedFile = staged,
            destination = destination,
            expectedBytes = fixture.toByteArray().size.toLong(),
            expectedSha256 = fixtureSha256
        )

        assertThat(published).isTrue()
        assertThat(destination.readText()).isEqualTo(fixture)
        assertThat(staged.exists()).isFalse()
    }
}
