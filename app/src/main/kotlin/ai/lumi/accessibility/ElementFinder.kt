package ai.lumi.accessibility

import android.content.res.Resources
import android.graphics.Rect
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a semantic element description to screen coordinates.
 *
 * Priority:
 *  1. Accessibility tree — exact bounds, fastest
 *  2. Pixel coordinates from VLM output — fallback for FLAG_SECURE screens
 */
@Singleton
class ElementFinder @Inject constructor() {

    /**
     * Resolve element bounds for [description].
     * [relativeX] and [relativeY] are normalised VLM fallback coords [0.0, 1.0].
     */
    fun find(
        description: String,
        relativeX: Float = 0f,
        relativeY: Float = 0f
    ): Rect? {
        // Primary: Accessibility tree
        val service = LumiAccessibilityService.instance
        if (service != null) {
            val treeBounds = service.findElementByDescription(description)
            if (treeBounds != null && !treeBounds.isEmpty) {
                Timber.d("ElementFinder: found '$description' via tree at $treeBounds")
                return treeBounds
            }

            // A live tree that does not contain the named target is stronger
            // evidence than a model-provided coordinate. Let TaskEngine recover
            // rather than placing the cursor over an unrelated launcher icon.
            Timber.w("ElementFinder: '$description' not present in live tree")
            return null
        }

        // Fallback: pixel coordinates from VLM screenshot output
        if (relativeX > 0f || relativeY > 0f) {
            val screenW = Resources.getSystem().displayMetrics.widthPixels
            val screenH = Resources.getSystem().displayMetrics.heightPixels
            val px = (relativeX * screenW).toInt()
            val py = (relativeY * screenH).toInt()
            val hitSize = 48 // ~48dp approximate tap target
            val fallback = Rect(px - hitSize, py - hitSize, px + hitSize, py + hitSize)
            Timber.d("ElementFinder: pixel fallback for '$description' → $fallback")
            return fallback
        }

        Timber.w("ElementFinder: could not resolve '$description'")
        return null
    }

    /** Returns the UI tree JSON string for the current screen. */
    fun captureUITree(): String =
        LumiAccessibilityService.instance?.captureUITree() ?: "{}"
}
