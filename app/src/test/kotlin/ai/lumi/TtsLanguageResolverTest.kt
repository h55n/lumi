package ai.lumi

import ai.lumi.voice.TtsLanguageResolver
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TtsLanguageResolverTest {
    @Test fun `English cloud response requested as Hindi uses English voice`() {
        assertThat(TtsLanguageResolver.resolve("Tap on Contacts", "hi-IN")).isEqualTo("en")
    }

    @Test fun `Devanagari Hindi response uses Hindi voice even with an app name`() {
        assertThat(TtsLanguageResolver.resolve("कॉन्टैक्ट्स पर टैप करें WhatsApp में", "hi-IN")).isEqualTo("hi")
    }

    @Test fun `Tamil response is spoken with Tamil locale`() {
        assertThat(TtsLanguageResolver.resolve("தொடர்புகளைத் தட்டவும்", "hi")).isEqualTo("ta")
    }
}
