package ai.lumi.cloud

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureKeyStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteSharedPreferences("lumi_secure")
        context.deleteSharedPreferences(TEST_PREFS)
        SecureKeyStore.init(context) {
            context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
        }
    }

    @Test
    fun `missing Sarvam key returns null instead of a bundled default`() {
        assertThat(SecureKeyStore.getSarvamKey()).isNull()
        assertThat(SecureKeyStore.getSarvamKeys()).isEmpty()
    }

    @Test
    fun `configured Sarvam key is read from the injected secure store`() {
        val userSuppliedKey = "test-only-user-supplied-key"
        SecureKeyStore.setSarvamKey(userSuppliedKey)

        assertThat(SecureKeyStore.getSarvamKey()).isEqualTo(userSuppliedKey)
        assertThat(SecureKeyStore.getSarvamKey(2)).isNull()
    }

    @Test
    fun `failed encrypted storage initialization clears legacy preferences and disables writes`() {
        context.getSharedPreferences("lumi_secure", Context.MODE_PRIVATE)
            .edit()
            .putString("api_key_sarvam_tts", "test-only-legacy-value")
            .commit()

        SecureKeyStore.init(context) {
            throw IllegalStateException("simulated encrypted storage failure")
        }
        SecureKeyStore.setSarvamKey("test-only-should-not-be-saved")

        assertThat(SecureKeyStore.getSarvamKey()).isNull()
        assertThat(SecureKeyStore.getSarvamKeys()).isEmpty()
        assertThat(context.getSharedPreferences("lumi_secure", Context.MODE_PRIVATE).all).isEmpty()
    }

    private companion object {
        const val TEST_PREFS = "secure_key_store_test"
    }
}
