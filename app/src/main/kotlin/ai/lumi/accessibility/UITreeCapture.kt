package ai.lumi.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

data class UIElement(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val depth: Int
) {
    /** Best human-readable label for this element. */
    val label: String? get() = text?.takeIf { it.isNotBlank() }
        ?: contentDescription?.takeIf { it.isNotBlank() }

    /** True if this element is a likely tap target. */
    val isTapTarget: Boolean get() = isClickable && !bounds.isEmpty
}

@Singleton
class UITreeCapture @Inject constructor() {

    /**
     * Flatten the full Accessibility tree into a list of [UIElement].
     * Filters to only interactive or labelled elements.
     */
    fun capture(): List<UIElement> {
        val service = LumiAccessibilityService.instance ?: return emptyList()
        val root = service.rootInActiveWindow ?: return emptyList()
        return flattenTree(root, 0).filter { it.label != null || it.isTapTarget }
    }

    /** Find all clickable elements on screen, sorted top-to-bottom. */
    fun clickableElements(): List<UIElement> =
        capture().filter { it.isTapTarget }.sortedBy { it.bounds.top }

    private fun flattenTree(node: AccessibilityNodeInfo, depth: Int): List<UIElement> {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val current = UIElement(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            bounds = bounds,
            isClickable = node.isClickable,
            depth = depth
        )
        val children = (0 until node.childCount)
            .mapNotNull { node.getChild(it) }
            .flatMap { flattenTree(it, depth + 1) }
        return listOf(current) + children
    }
}
