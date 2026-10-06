package ai.lumi.inference

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

class ModelRuntimeGateTest {
    @Test
    fun `unimplemented runtime model is not downloaded cached or sideloaded`() = runBlocking {
        val model = ModelSpec.MOONDREAM2_Q4_K
        val manager = ModelDownloadManager(
            context = mockk<Context>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true)
        )

        val states = manager.download(model).toList()

        assertThat(states).containsExactly(
            DownloadState.CloudMode(
                model,
                "Local inference runtime is not integrated — using Cloud mode"
            )
        )
        assertThat(manager.isDownloaded(model)).isFalse()
        assertThat(manager.sideloadFromPath(model, "unused-path")).isFalse()
    }
}
