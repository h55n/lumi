package ai.lumi.uimap

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 64-bit perceptual hash (pHash) for screen matching.
 * Two frames of the same screen → Hamming distance < 10.
 * Two different screens → Hamming distance ≥ 15.
 */
@Singleton
class PerceptualHasher @Inject constructor() {

    companion object {
        private const val HASH_SIZE = 8               // 8×8 = 64 bits
        private const val DCT_SIZE = 32               // Downsample to 32×32 before DCT
        const val MATCH_THRESHOLD = 10                // Hamming distance for "same screen"
    }

    /** Compute 64-bit pHash of [bitmap]. */
    fun hash(bitmap: Bitmap): Long {
        // 1. Resize to DCT_SIZE × DCT_SIZE
        val small = Bitmap.createScaledBitmap(bitmap, DCT_SIZE, DCT_SIZE, true)

        // 2. Convert to greyscale floats
        val pixels = FloatArray(DCT_SIZE * DCT_SIZE) { i ->
            val pixel = small.getPixel(i % DCT_SIZE, i / DCT_SIZE)
            (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f)
        }

        // 3. Apply 2D DCT
        val dct = dct2D(pixels, DCT_SIZE)

        // 4. Extract top-left HASH_SIZE×HASH_SIZE (low frequencies)
        val dctLowFreq = FloatArray(HASH_SIZE * HASH_SIZE) { i ->
            dct[(i / HASH_SIZE) * DCT_SIZE + (i % HASH_SIZE)]
        }

        // 5. Compute mean (excluding DC component at [0])
        val mean = dctLowFreq.drop(1).average().toFloat()

        // 6. Build 64-bit hash: 1 if above mean, 0 if below
        var hashBits = 0L
        for (i in dctLowFreq.indices) {
            if (dctLowFreq[i] >= mean) hashBits = hashBits or (1L shl i)
        }
        return hashBits
    }

    /** Hamming distance between two 64-bit hashes. */
    fun hammingDistance(a: Long, b: Long): Int =
        java.lang.Long.bitCount(a xor b)

    /** Returns true if [a] and [b] represent the same screen. */
    fun isSameScreen(a: Long, b: Long): Boolean =
        hammingDistance(a, b) < MATCH_THRESHOLD

    // ── DCT implementation ──────────────────────────────────────────────────────

    private fun dct2D(pixels: FloatArray, n: Int): FloatArray {
        val temp = FloatArray(n * n)
        // Apply 1D DCT to each row
        for (row in 0 until n) {
            val rowData = FloatArray(n) { col -> pixels[row * n + col] }
            val dctRow = dct1D(rowData)
            for (col in 0 until n) temp[row * n + col] = dctRow[col]
        }
        val result = FloatArray(n * n)
        // Apply 1D DCT to each column
        for (col in 0 until n) {
            val colData = FloatArray(n) { row -> temp[row * n + col] }
            val dctCol = dct1D(colData)
            for (row in 0 until n) result[row * n + col] = dctCol[row]
        }
        return result
    }

    private fun dct1D(input: FloatArray): FloatArray {
        val n = input.size
        return FloatArray(n) { k ->
            var sum = 0.0
            for (i in 0 until n) {
                sum += input[i] * cos(Math.PI * k * (2 * i + 1) / (2 * n))
            }
            (sum * if (k == 0) sqrt(1.0 / n) else sqrt(2.0 / n)).toFloat()
        }
    }
}
