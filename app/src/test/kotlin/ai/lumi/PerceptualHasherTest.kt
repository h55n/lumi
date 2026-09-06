package ai.lumi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import ai.lumi.uimap.PerceptualHasher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerceptualHasherTest {

    private lateinit var hasher: PerceptualHasher

    @Before
    fun setUp() { hasher = PerceptualHasher() }

    @Test
    fun `same bitmap produces identical hash`() {
        val bmp = createSolidBitmap(Color.RED)
        val h1 = hasher.hash(bmp)
        val h2 = hasher.hash(bmp)
        assertThat(h1).isEqualTo(h2)
    }

    @Test
    fun `nearly identical bitmaps have low hamming distance`() {
        val bmp1 = createGradientBitmap(200, 200)
        val bmp2 = createGradientBitmap(200, 200).apply {
            setPixel(10, 10, Color.BLACK) // tiny localized pixel change
        }
        val h1 = hasher.hash(bmp1)
        val h2 = hasher.hash(bmp2)
        val dist = hasher.hammingDistance(h1, h2)
        assertThat(dist).isLessThan(PerceptualHasher.MATCH_THRESHOLD)
    }

    @Test
    fun `completely different bitmaps have high hamming distance`() {
        val bmp1 = createNoiseBitmap(12345L)
        val bmp2 = createNoiseBitmap(67890L)
        val h1 = hasher.hash(bmp1)
        val h2 = hasher.hash(bmp2)
        val dist = hasher.hammingDistance(h1, h2)
        assertThat(dist).isAtLeast(PerceptualHasher.MATCH_THRESHOLD)
    }

    @Test
    fun `isSameScreen returns true for same bitmap`() {
        val bmp = createNoiseBitmap(12345L)
        val h = hasher.hash(bmp)
        assertThat(hasher.isSameScreen(h, h)).isTrue()
    }

    @Test
    fun `isSameScreen returns false for different bitmaps`() {
        val h1 = hasher.hash(createNoiseBitmap(12345L))
        val h2 = hasher.hash(createNoiseBitmap(67890L))
        assertThat(hasher.isSameScreen(h1, h2)).isFalse()
    }

    @Test
    fun `hamming distance is symmetric`() {
        val h1 = hasher.hash(createSolidBitmap(Color.GREEN))
        val h2 = hasher.hash(createSolidBitmap(Color.YELLOW))
        assertThat(hasher.hammingDistance(h1, h2))
            .isEqualTo(hasher.hammingDistance(h2, h1))
    }

    @Test
    fun `hash runs on 1280x720 bitmap without crash`() {
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.GRAY)
        val hash = hasher.hash(bmp)
        assertThat(hash).isNotEqualTo(0L)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun createNoiseBitmap(seed: Long, w: Int = 64, h: Int = 64): Bitmap {
        val rand = java.util.Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, Color.rgb(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256)))
            }
        }
        return bmp
    }

    private fun createSolidBitmap(color: Int, w: Int = 32, h: Int = 32): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    private fun createGradientBitmap(w: Int = 32, h: Int = 32): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, Color.rgb((x * 255) / w, (y * 255) / h, 150))
            }
        }
        return bmp
    }

    private fun createHorizontalPattern(w: Int = 32, h: Int = 32): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val color = if (y < h / 2) Color.WHITE else Color.BLACK
            for (x in 0 until w) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }

    private fun createVerticalPattern(w: Int = 32, h: Int = 32): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) {
            val color = if (x < w / 2) Color.WHITE else Color.BLACK
            for (y in 0 until h) {
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }
}
