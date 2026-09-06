package ai.lumi

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import ai.lumi.screencapture.ScreenshotProcessor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotProcessorTest {

    private lateinit var processor: ScreenshotProcessor

    @Before fun setUp() { processor = ScreenshotProcessor() }

    @Test fun `process outputs correct target dimensions`() {
        val input = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val result = processor.process(input)
        assertThat(result.width).isEqualTo(ScreenshotProcessor.TARGET_WIDTH)
        assertThat(result.height).isEqualTo(ScreenshotProcessor.TARGET_HEIGHT)
    }

    @Test fun `process already-correct size returns same dimensions`() {
        val input = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val result = processor.process(input)
        assertThat(result.width).isEqualTo(1280)
        assertThat(result.height).isEqualTo(720)
    }

    @Test fun `process landscape 2560x1440 outputs 1280x720`() {
        val input = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        val result = processor.process(input)
        assertThat(result.width).isEqualTo(1280)
        assertThat(result.height).isEqualTo(720)
    }

    @Test fun `diffFraction of identical bitmaps is zero`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val diff = processor.diffFraction(bmp, bmp)
        assertThat(diff).isEqualTo(0f)
    }

    @Test fun `diffFraction of different-size bitmaps is 1`() {
        val a = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        assertThat(processor.diffFraction(a, b)).isEqualTo(1.0f)
    }

    @Test fun `diffFraction is between 0 and 1`() {
        val a = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // Draw different pixels in b
        for (x in 0 until 50) for (y in 0 until 50) b.setPixel(x, y, android.graphics.Color.RED)
        val diff = processor.diffFraction(a, b)
        assertThat(diff).isAtLeast(0f)
        assertThat(diff).isAtMost(1f)
    }
}
