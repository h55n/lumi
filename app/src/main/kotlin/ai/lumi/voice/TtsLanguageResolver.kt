package ai.lumi.voice

/**
 * Chooses the TTS locale from the text that will actually be spoken.
 * Cloud providers occasionally ignore the requested output language; using the
 * requested Indic voice for an English sentence makes the guidance unintelligible.
 */
object TtsLanguageResolver {
    fun resolve(text: String, requestedLanguage: String): String {
        val requested = requestedLanguage.substringBefore('-').lowercase()
        if (requested == "en") return "en"

        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return requested

        val devanagari = letters.count { it in '\u0900'..'\u097F' }
        val tamil = letters.count { it in '\u0B80'..'\u0BFF' }
        val telugu = letters.count { it in '\u0C00'..'\u0C7F' }
        val bengali = letters.count { it in '\u0980'..'\u09FF' }
        val gujarati = letters.count { it in '\u0A80'..'\u0AFF' }
        val gurmukhi = letters.count { it in '\u0A00'..'\u0A7F' }
        val kannada = letters.count { it in '\u0C80'..'\u0CFF' }
        val malayalam = letters.count { it in '\u0D00'..'\u0D7F' }
        val latin = letters.count { it in 'A'..'Z' || it in 'a'..'z' }

        val detected = listOf(
            "hi" to devanagari, "ta" to tamil, "te" to telugu, "bn" to bengali,
            "gu" to gujarati, "pa" to gurmukhi, "kn" to kannada, "ml" to malayalam
        ).maxBy { it.second }

        // Only override when the detected script is dominant. This preserves Hindi
        // guidance containing an English app name such as "WhatsApp".
        return when {
            detected.second > latin && detected.second > 0 -> detected.first
            latin > detected.second -> "en"
            else -> requested
        }
    }
}
