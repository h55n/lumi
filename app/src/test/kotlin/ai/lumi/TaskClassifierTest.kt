package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.TaskClassifier
import ai.lumi.engine.TaskType
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class TaskClassifierTest {

    private lateinit var classifier: TaskClassifier

    @Before
    fun setUp() {
        val appInventory = mockk<ai.lumi.engine.AppInventory>()
        every { appInventory.resolvePackage(any()) } returns null
        every { appInventory.firstInstalled(any()) } returns null
        classifier = TaskClassifier(appInventory)
    }

    // ── UPI / Payment ────────────────────────────────────────────────────────────

    @Test fun `English pay keyword classifies as UPI_PAYMENT`() {
        assertThat(classifier.classify("Send money to maa")).isEqualTo(TaskType.UPI_PAYMENT)
    }

    @Test fun `Hindi payment keyword classifies as UPI_PAYMENT`() {
        assertThat(classifier.classify("500 rupaye bhejo")).isEqualTo(TaskType.UPI_PAYMENT)
    }

    @Test fun `PhonePe keyword classifies as UPI_PAYMENT`() {
        assertThat(classifier.classify("PhonePe par paise bhejo")).isEqualTo(TaskType.UPI_PAYMENT)
    }

    @Test fun `GPay keyword classifies as UPI_PAYMENT`() {
        assertThat(classifier.classify("gpay se transfer karo")).isEqualTo(TaskType.UPI_PAYMENT)
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────────

    @Test fun `WhatsApp keyword classifies as WHATSAPP_MESSAGE`() {
        assertThat(classifier.classify("WhatsApp par message karo")).isEqualTo(TaskType.WHATSAPP_MESSAGE)
    }

    @Test fun `message keyword classifies as WHATSAPP_MESSAGE`() {
        assertThat(classifier.classify("send a message to dad")).isEqualTo(TaskType.WHATSAPP_MESSAGE)
    }

    // ── Settings ─────────────────────────────────────────────────────────────────

    @Test fun `WiFi keyword classifies as SETTINGS_WIFI`() {
        assertThat(classifier.classify("turn on wifi")).isEqualTo(TaskType.SETTINGS_WIFI)
    }

    @Test fun `Hindi WiFi keyword classifies as SETTINGS_WIFI`() {
        assertThat(classifier.classify("wifi lagao")).isEqualTo(TaskType.SETTINGS_WIFI)
    }

    @Test fun `Bluetooth keyword classifies as SETTINGS_BLUETOOTH`() {
        assertThat(classifier.classify("enable bluetooth")).isEqualTo(TaskType.SETTINGS_BLUETOOTH)
    }

    @Test fun `Display keyword classifies as SETTINGS_DISPLAY`() {
        assertThat(classifier.classify("open display settings")).isEqualTo(TaskType.SETTINGS_DISPLAY)
        assertThat(classifier.classify("dark mode lagao")).isEqualTo(TaskType.SETTINGS_DISPLAY)
    }

    @Test fun `Settings main keyword classifies as SETTINGS_MAIN`() {
        assertThat(classifier.classify("phone settings kholo")).isEqualTo(TaskType.SETTINGS_MAIN)
    }

    // ── Call ─────────────────────────────────────────────────────────────────────

    @Test fun `call keyword classifies as PHONE_CALL`() {
        assertThat(classifier.classify("call my brother")).isEqualTo(TaskType.PHONE_CALL)
    }

    @Test fun `Hindi call keyword classifies as PHONE_CALL`() {
        assertThat(classifier.classify("bhai ko call karo")).isEqualTo(TaskType.PHONE_CALL)
    }

    // ── Camera ───────────────────────────────────────────────────────────────────

    @Test fun `photo keyword classifies as CAMERA`() {
        assertThat(classifier.classify("take a photo")).isEqualTo(TaskType.CAMERA)
    }

    @Test fun `selfie keyword classifies as CAMERA`() {
        assertThat(classifier.classify("selfie lo")).isEqualTo(TaskType.CAMERA)
    }

    // ── Unknown ──────────────────────────────────────────────────────────────────

    @Test fun `unrecognised text returns UNKNOWN`() {
        assertThat(classifier.classify("xyz abc random")).isEqualTo(TaskType.UNKNOWN)
    }

    @Test fun `empty string returns UNKNOWN`() {
        assertThat(classifier.classify("")).isEqualTo(TaskType.UNKNOWN)
    }

    // ── Package mapping ──────────────────────────────────────────────────────────

    @Test fun `UPI_PAYMENT maps to PhonePe package`() {
        assertThat(classifier.packageForTask(TaskType.UPI_PAYMENT)).isEqualTo("com.phonepe.app")
    }

    @Test fun `WHATSAPP_MESSAGE maps to WhatsApp package`() {
        assertThat(classifier.packageForTask(TaskType.WHATSAPP_MESSAGE)).isEqualTo("com.whatsapp")
    }

    @Test fun `UNKNOWN returns null package`() {
        assertThat(classifier.packageForTask(TaskType.UNKNOWN)).isNull()
    }

    // ── Case insensitivity ────────────────────────────────────────────────────────

    @Test fun `classifier is case-insensitive`() {
        assertThat(classifier.classify("PHONEPE PAR PAISE")).isEqualTo(TaskType.UPI_PAYMENT)
        assertThat(classifier.classify("WHATSAPP MESSAGE")).isEqualTo(TaskType.WHATSAPP_MESSAGE)
    }
}
