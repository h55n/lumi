package ai.lumi.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.lumi.ui.components.*
import ai.lumi.ui.theme.*
import ai.lumi.cloud.SecureKeyStore
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Home screen shown when Lumi is active.
 * Redesigned: dark premium surface, honest cloud/offline status,
 * quick-access controls, and auto-tap toggle.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onStopLumi: () -> Unit
) {
    val context = LocalContext.current

    // Detect connection mode honestly
    val hasCloudKey = remember { SecureKeyStore.hasAnyKey() }
    val modeLabel = if (hasCloudKey) "Cloud AI Active" else "No AI Key — Limited"
    val modeDot = if (hasCloudKey) Color(0xFF22C55E) else Color(0xFFDC2626)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LumiCream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))

        // ── Status chip ──────────────────────────────────────────────────────
        LumiStatusChip(label = modeLabel, dotColor = modeDot)

        Spacer(Modifier.height(28.dp))

        // ── Logo + headline ──────────────────────────────────────────────────
        Text(
            "✦",
            fontSize = 52.sp,
            color = LumiSaffron,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Lumi",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = LumiInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Your phone assistant is ready.\nTap the saffron bubble when you need help.",
            fontSize = 14.sp,
            color = LumiMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(32.dp))

        // ── How to use card ──────────────────────────────────────────────────
        LumiCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "How to use",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = LumiSaffron,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(16.dp))
            LumiInfoRow("👆", "Tap the bubble", "Ask for help with any task")
            Spacer(Modifier.height(12.dp))
            LumiInfoRow("🔁", "Tap again", "Cancel current guidance")
            Spacer(Modifier.height(12.dp))
            LumiInfoRow("↔️", "Drag the bubble", "Move it anywhere on screen")
            Spacer(Modifier.height(12.dp))
            LumiInfoRow("🗣️", "Speak your language", "Hindi, English & more")
        }

        Spacer(Modifier.height(16.dp))

        // Auto-tap is intentionally configured only in Settings, where the
        // persisted value and its safety explanation are shown together.
        Spacer(Modifier.height(24.dp))

        // ── Quick demos ──────────────────────────────────────────────────────
        Text(
            "QUICK DEMOS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = LumiMuted,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(Modifier.height(10.dp))

        LumiOutlineButton("Turn on Wi-Fi", onClick = {
            val intent = Intent("ai.lumi.TRANSCRIPT").apply {
                setPackage(context.packageName)
                putExtra("text", "Turn on Wi-Fi")
                putExtra("language", "en")
            }
            context.sendBroadcast(intent)
        })
        Spacer(Modifier.height(10.dp))

        LumiOutlineButton("Display & Dark Mode", onClick = {
            val intent = Intent("ai.lumi.TRANSCRIPT").apply {
                setPackage(context.packageName)
                putExtra("text", "Open Display settings")
                putExtra("language", "en")
            }
            context.sendBroadcast(intent)
        })

        Spacer(Modifier.height(24.dp))

        // ── Settings + stop ──────────────────────────────────────────────────
        LumiButton("Settings", onClick = onOpenSettings)
        Spacer(Modifier.height(10.dp))
        LumiOutlineButton("Stop Lumi", onClick = onStopLumi, destructive = true)

        Spacer(Modifier.height(36.dp))
    }
}
