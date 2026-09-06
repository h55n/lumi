package ai.lumi

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import ai.lumi.engine.TaskClassifier
import ai.lumi.engine.TaskType
import ai.lumi.uimap.PerceptualHasher
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration tests.
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LumiIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── TaskClassifier — on-device ─────────────────────────────────────────────

    @Test fun classifierRunsOnDevice() {
        val classifier = TaskClassifier()
        assertThat(classifier.classify("PhonePe par paise bhejo")).isEqualTo(TaskType.UPI_PAYMENT)
    }

    // ── PerceptualHasher — on-device (requires Android Bitmap) ────────────────

    @Test fun pHasherProducesConsistentResults() {
        val hasher = PerceptualHasher()
        val bmp = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.rgb(200, 150, 100))
        }
        val h1 = hasher.hash(bmp)
        val h2 = hasher.hash(bmp)
        assertThat(h1).isEqualTo(h2)
        assertThat(hasher.hammingDistance(h1, h2)).isEqualTo(0)
    }

    // ── Package name ───────────────────────────────────────────────────────────

    @Test fun packageNameIsCorrect() {
        assertThat(context.packageName).isEqualTo("ai.lumi.debug")
    }

    // ── Assets — bundle maps ───────────────────────────────────────────────────

    @Test fun phonePeBundleMapExists() {
        val assets = context.assets.list("uimaps") ?: emptyArray()
        assertThat(assets.toList()).contains("phonepe_maps.json")
    }

    @Test fun whatsappBundleMapExists() {
        val assets = context.assets.list("uimaps") ?: emptyArray()
        assertThat(assets.toList()).contains("whatsapp_maps.json")
    }

    @Test fun phonePeMapIsValidJson() {
        val json = context.assets.open("uimaps/phonepe_maps.json").bufferedReader().readText()
        assertThat(json).isNotEmpty()
        assertThat(json).contains("com.phonepe.app")
        assertThat(json).contains("guidancePlan")
    }

    @Test fun whatsappMapIsValidJson() {
        val json = context.assets.open("uimaps/whatsapp_maps.json").bufferedReader().readText()
        assertThat(json).isNotEmpty()
        assertThat(json).contains("com.whatsapp")
    }
}
