package ai.lumi.engine

import android.provider.AlarmClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentLibraryTest {
    @Test
    fun `torch off is classified as off and never as on`() {
        val match = checkNotNull(IntentLibrary.classify("torch off"))

        assertThat(match.intent).isEqualTo(SystemIntent.FLASHLIGHT)
        assertThat(match.extractedParam).isEqualTo("off")
        assertThat(IntentLibrary.flashlightEnabled(match)).isFalse()
    }

    @Test
    fun `torch on is classified as on`() {
        val match = checkNotNull(IntentLibrary.classify("turn torch on"))

        assertThat(match.intent).isEqualTo(SystemIntent.FLASHLIGHT)
        assertThat(IntentLibrary.flashlightEnabled(match)).isTrue()
    }

    @Test
    fun `ambiguous flashlight request is not executed as a direct action`() {
        assertThat(IntentLibrary.classify("torch")).isNull()
        assertThat(IntentLibrary.classify("torch on and off")).isNull()
    }

    @Test
    fun `timer duration requires an explicit unit`() {
        val explicit = IntentLibrary.classify("start timer for 10 minutes")
        val ambiguous = IntentLibrary.classify("start timer 10")

        assertThat(explicit?.intent).isEqualTo(SystemIntent.TIMER)
        assertThat(explicit?.extractedParam).isEqualTo("600")
        assertThat(ambiguous?.intent).isEqualTo(SystemIntent.TIMER)
        assertThat(ambiguous?.extractedParam).isNull()
    }

    @Test
    fun `timer intent always leaves the clock confirmation UI enabled`() {
        val intent = IntentLibrary.buildTimerIntent(600)

        assertThat(intent.action).isEqualTo(AlarmClock.ACTION_SET_TIMER)
        assertThat(intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1)).isEqualTo(600)
        assertThat(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)).isFalse()
    }

    @Test
    fun `timer without a validated duration opens without pre-filling a length`() {
        val intent = IntentLibrary.buildTimerIntent(null)

        assertThat(intent.hasExtra(AlarmClock.EXTRA_LENGTH)).isFalse()
        assertThat(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)).isFalse()
    }
}
