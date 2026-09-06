package ai.lumi.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ai.lumi.uimap.PerceptualHasher
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Memory-Safe Screenshot Pipeline (PRD Section 3.3).
 * Allocates a single reusable 320x320 static buffer (RGB_565 = ~200KB) for post-tap verification.
 * No Bitmap reference escapes this class.
 */
@Singleton
class ScreenshotBuffer @Inject constructor(
    private val perceptualHasher: PerceptualHasher
) {
    companion object {
        const val BUFFER_SIZE = 320
    }

    private val lock = ReentrantLock()
    private var buffer: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        initialize()
    }

    @Synchronized
    fun initialize() {
        if (buffer == null || buffer?.isRecycled == true) {
            buffer = Bitmap.createBitmap(BUFFER_SIZE, BUFFER_SIZE, Bitmap.Config.RGB_565)
        }
    }

    /**
     * Draws [source] into the reusable 320x320 buffer, computes its 64-bit pHash,
     * and clears the canvas. Returns only the hash (Long), never the Bitmap.
     */
    fun computeQuadrantHash(source: Bitmap, regionOfInterest: Rect? = null): Long {
        lock.withLock {
            val bmp = buffer ?: run {
                initialize()
                buffer ?: return 0L
            }

            val canvas = Canvas(bmp)
            val srcRect = regionOfInterest ?: Rect(0, 0, source.width, source.height)
            val dstRect = Rect(0, 0, BUFFER_SIZE, BUFFER_SIZE)

            canvas.drawBitmap(source, srcRect, dstRect, paint)
            val hash = perceptualHasher.hash(bmp)
            canvas.setBitmap(null)
            return hash
        }
    }

    fun destroy() {
        lock.withLock {
            buffer?.recycle()
            buffer = null
        }
    }
}
