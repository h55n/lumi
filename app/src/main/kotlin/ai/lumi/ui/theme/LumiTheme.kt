package ai.lumi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

// ── Lumi Design Tokens ──────────────────────────────────────────────────────────

/** Lumi Saffron — primary accent, cursor, bubble. High contrast on dark. */
val LumiSaffron = Color(0xFFF5A100)

/** Lumi Saffron dim — for secondary/muted accent use */
val LumiSaffronDim = Color(0xFF4A3000)

/** Lumi Ink — text on light surfaces. */
val LumiInk = Color(0xFF1A1A2E)

/** Lumi Cream — the app background. */
val LumiCream = Color(0xFFFFFAF3)

/** Deprecated compatibility token; all app surfaces use Lumi Cream. */
val LumiDark = LumiCream

/** Lumi Surface — card backgrounds on dark. */
val LumiSurface = LumiCream

/** Lumi Surface Elevated — slightly lighter card for emphasis. */
val LumiSurfaceElevated = Color(0xFFFFF3DC)

/** Lumi Border — subtle card borders on dark surfaces. */
val LumiBorder = LumiInk.copy(alpha = 0.18f)

/** Lumi Muted — secondary text on dark surfaces. */
val LumiMuted = LumiInk.copy(alpha = 0.72f)

/** Listening Green — bubble mic-active state. */
val ListeningGreen = Color(0xFF22C55E)

/** Success Green — task complete confirmation. */
val SuccessGreen = Color(0xFF16A34A)

/** Error Red — errors ONLY. Never used as navigation cue. */
val ErrorRed = Color(0xFFDC2626)

/** Light surface variant (onboarding). */
val SaffronLight = Color(0xFFFFF3DC)

// ── Color Schemes ──────────────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = LumiSaffron,
    onPrimary = LumiInk,
    primaryContainer = SaffronLight,
    onPrimaryContainer = LumiInk,
    secondary = LumiInk,
    onSecondary = LumiCream,
    background = LumiCream,
    onBackground = LumiInk,
    surface = LumiCream,
    onSurface = LumiInk,
    surfaceVariant = SaffronLight,
    onSurfaceVariant = LumiInk,
    error = ErrorRed,
    onError = Color.White,
    outline = Color(0xFFD4C5A9)
)

// ── Typography ──────────────────────────────────────────────────────────────────

val LumiTypography = Typography(
    // Guidance bubble instruction — 20sp bold
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    // Body text — 18sp
    bodyLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
    // Settings / supplementary — 16sp
    bodyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    // Labels
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    // Bubble status — 16sp medium
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
)

// ── Theme Composable ───────────────────────────────────────────────────────────

@Composable
fun LumiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = LumiTypography,
        content = content
    )
}
