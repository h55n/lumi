package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.ActionTemplateEngine
import ai.lumi.engine.ElementMatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionTemplateEngineTest {

    private lateinit var engine: ActionTemplateEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val elementMatcher = ElementMatcher()
        engine = ActionTemplateEngine(context, elementMatcher)
    }

    @Test
    fun `bundled PhonePe template is loaded correctly`() {
        val template = engine.getTemplate("com.phonepe.app", "send_money")
        assertThat(template).isNotNull()
        assertThat(template!!.steps).isNotEmpty()
        assertThat(template.steps.first().stepId).isEqualTo("tap_pay")
    }

    @Test
    fun `bundled WhatsApp template is loaded correctly`() {
        val template = engine.getTemplate("com.whatsapp", "send_message")
        assertThat(template).isNotNull()
        assertThat(template!!.steps).isNotEmpty()
    }

    @Test
    fun `createGuidancePlan converts steps into TaskStep list`() {
        val template = engine.getTemplate("com.phonepe.app", "send_money")
        assertThat(template).isNotNull()

        val plan = engine.createGuidancePlan(template!!)
        assertThat(plan).isNotEmpty()
        assertThat(plan.first().instruction("en")).isEqualTo(template.steps.first().tts_en)
        assertThat(plan.first().instruction("hi")).isEqualTo(template.steps.first().tts_hi)
        assertThat(plan.last().isLastStep).isTrue()
    }

    @Test
    fun `unknown template returns null`() {
        val template = engine.getTemplate("com.nonexistent.app", "do_magic")
        assertThat(template).isNull()
    }
}
