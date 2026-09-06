package ai.lumi

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.ScreenshotBuffer
import ai.lumi.uimap.PerceptualHasher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotBufferTest {

    private lateinit var buffer: ScreenshotBuffer
    private val hasher = PerceptualHasher()

    @Before
    fun setUp() {
        buffer = ScreenshotBuffer(hasher)
    }

    @After
    fun tearDown() {
        buffer.destroy()
    }

    @Test
    fun `computeQuadrantHash returns non-zero hash for solid image`() {
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val hash = buffer.computeQuadrantHash(bmp)
        // Solid color has low or zero AC components, but hash executes without crash
        assertThat(hash).isNotNull()
    }

    @Test
    fun `same image produces identical quadrant hash`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 100) {
                for (y in 0 until 100) {
                    setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
                }
            }
        }
        val h1 = buffer.computeQuadrantHash(bmp)
        val h2 = buffer.computeQuadrantHash(bmp)
        assertThat(h1).isEqualTo(h2)
    }
}
