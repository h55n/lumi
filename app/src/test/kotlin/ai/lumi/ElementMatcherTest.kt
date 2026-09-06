package ai.lumi

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.ElementMatcher
import ai.lumi.engine.ParsedIntent
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElementMatcherTest {

    private lateinit var matcher: ElementMatcher

    @Before
    fun setUp() {
        matcher = ElementMatcher()
    }

    @Test
    fun `invisible node scores zero`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns false
        every { node.isEnabled } returns true

        val intent = ParsedIntent(actionType = "CLICK", entityLabels = listOf("Pay"))
        assertThat(matcher.scoreNode(node, intent)).isEqualTo(0f)
    }

    @Test
    fun `disabled node scores zero`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns true
        every { node.isEnabled } returns false

        val intent = ParsedIntent(actionType = "CLICK", entityLabels = listOf("Pay"))
        assertThat(matcher.scoreNode(node, intent)).isEqualTo(0f)
    }

    @Test
    fun `resourceId match adds 1_0 to score`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns true
        every { node.isEnabled } returns true
        every { node.text } returns null
        every { node.contentDescription } returns null
        every { node.hintText } returns null
        every { node.viewIdResourceName } returns "com.phonepe.app:id/btn_pay"
        every { node.isClickable } returns false
        every { node.isFocusable } returns false
        every { node.className } returns "android.view.View"

        val intent = ParsedIntent(
            actionType = "CLICK",
            entityLabels = emptyList(),
            entityResourceIds = listOf("btn_pay")
        )
        assertThat(matcher.scoreNode(node, intent)).isEqualTo(1.0f)
    }

    @Test
    fun `Hindi label match adds 0_8 to score`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns true
        every { node.isEnabled } returns true
        every { node.text } returns "पैसे भेजें"
        every { node.contentDescription } returns null
        every { node.hintText } returns null
        every { node.viewIdResourceName } returns null
        every { node.isClickable } returns false
        every { node.isFocusable } returns false
        every { node.className } returns "android.widget.TextView"

        val intent = ParsedIntent(
            actionType = "CLICK",
            entityLabels = listOf("पैसे भेजें", "Pay")
        )
        assertThat(matcher.scoreNode(node, intent)).isEqualTo(0.8f)
    }

    @Test
    fun `interactive button with matching label gets high confidence`() {
        val node = mockk<AccessibilityNodeInfo>()
        every { node.isVisibleToUser } returns true
        every { node.isEnabled } returns true
        every { node.text } returns "Pay Now"
        every { node.contentDescription } returns null
        every { node.hintText } returns null
        every { node.viewIdResourceName } returns "com.phonepe.app:id/btn_proceed_pay"
        every { node.isClickable } returns true
        every { node.isFocusable } returns false
        every { node.className } returns "android.widget.Button"

        val intent = ParsedIntent(
            actionType = "CLICK",
            entityLabels = listOf("Pay Now", "Pay"),
            entityResourceIds = listOf("btn_proceed_pay"),
            expectedClass = "android.widget.Button"
        )
        // 1.0 (resourceId) + 0.8 (label) + 0.2 (clickable) + 0.3 (class) = 2.3f
        assertThat(matcher.scoreNode(node, intent)).isEqualTo(2.3f)
    }
}
