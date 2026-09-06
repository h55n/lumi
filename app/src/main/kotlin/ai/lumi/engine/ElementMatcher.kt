package ai.lumi.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parsed intent used by [ElementMatcher] to locate on-screen UI elements.
 */
data class ParsedIntent(
    val actionType: String,
    val entityLabels: List<String>,                      // e.g. ["Pay", "पे", "पेमेंट", "Send"]
    val entityResourceIds: List<String> = emptyList(),   // e.g. ["pay_button", "btn_pay"]
    val expectedClass: String? = null                    // e.g. "android.widget.Button"
)

data class ElementMatchResult(
    val bounds: Rect,
    val score: Float,
    val matchedLabel: String? = null
)

/**
 * Deterministic Element Matcher (PRD Section 3.1).
 * The primary guidance engine — never calls a model. Runs in-process in <80ms.
 */
@Singleton
class ElementMatcher @Inject constructor() {

    fun scoreNode(node: AccessibilityNodeInfo, intent: ParsedIntent): Float {
        // Penalty: invisible or disabled nodes cannot be targeted
        if (!node.isVisibleToUser || !node.isEnabled) return 0f

        var score = 0f
        val text = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString()
        ).joinToString(" ").lowercase().trim()

        val resourceId = node.viewIdResourceName?.substringAfterLast("/")?.lowercase().orEmpty()

        // Tier 1: exact resource ID match (highest confidence)
        if (intent.entityResourceIds.any { idHint -> resourceId.contains(idHint.lowercase()) }) {
            score += 1.0f
        }

        // Tier 2: label match in any supported language (Hindi, English, etc.)
        if (intent.entityLabels.any { label -> text.contains(label.lowercase().trim()) }) {
            score += 0.8f
        }

        // Tier 3: element is interactive
        if (node.isClickable || node.isFocusable) {
            score += 0.2f
        }

        // Tier 4: class matches expected (e.g., Button vs TextView)
        if (intent.expectedClass != null && node.className?.toString() == intent.expectedClass) {
            score += 0.3f
        }

        return score
    }

    /**
     * Traverses the live [root] AccessibilityNodeInfo tree and returns the best matching
     * physical screen bounds with score >= [threshold].
     */
    fun findBestMatch(
        root: AccessibilityNodeInfo?,
        intent: ParsedIntent,
        threshold: Float = 0.7f
    ): ElementMatchResult? {
        if (root == null) return null

        val startTime = System.currentTimeMillis()
        var bestScore = 0f
        var bestBounds: Rect? = null
        var bestMatchedLabel: String? = null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val score = scoreNode(node, intent)

            if (score > bestScore && score >= threshold) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    bestScore = score
                    bestBounds = rect
                    bestMatchedLabel = node.text?.toString() ?: node.contentDescription?.toString()
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("ElementMatcher: evaluated in %d ms, bestScore=%.2f, bounds=%s", elapsed, bestScore, bestBounds)

        return if (bestBounds != null) {
            ElementMatchResult(bounds = bestBounds, score = bestScore, matchedLabel = bestMatchedLabel)
        } else {
            null
        }
    }
}
