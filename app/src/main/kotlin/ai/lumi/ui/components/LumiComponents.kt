package ai.lumi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.lumi.ui.theme.*

// ── LumiButton ─────────────────────────────────────────────────────────────────

@Composable
fun LumiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LumiSaffron,
            contentColor = LumiInk,
            disabledContainerColor = LumiSaffron.copy(alpha = 0.3f),
            disabledContentColor = LumiInk.copy(alpha = 0.4f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    }
}

@Composable
fun LumiOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    val borderColor = if (destructive) ErrorRed.copy(alpha = 0.7f) else LumiInk.copy(alpha = 0.25f)
    val textColor = if (destructive) ErrorRed else LumiInk
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = textColor,
            containerColor = Color.Transparent
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

// ── LumiCard ───────────────────────────────────────────────────────────────────

@Composable
fun LumiCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LumiSurface)
            .border(1.dp, LumiBorder, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

// ── LumiStatusChip ─────────────────────────────────────────────────────────────

@Composable
fun LumiStatusChip(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(LumiSurface)
            .border(1.dp, LumiBorder, RoundedCornerShape(100.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFCCCCDD),
            letterSpacing = 0.3.sp
        )
    }
}

// ── LumiToggleCard ─────────────────────────────────────────────────────────────

@Composable
fun LumiToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeBorder = if (checked) LumiSaffron.copy(alpha = 0.3f) else LumiBorder
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LumiSurface)
            .border(1.dp, activeBorder, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LumiInk)
                Spacer(Modifier.height(3.dp))
                Text(description, fontSize = 16.sp, color = LumiMuted, lineHeight = 22.sp)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LumiInk,
                    checkedTrackColor = LumiSaffron,
                    uncheckedThumbColor = LumiMuted,
                    uncheckedTrackColor = LumiSurfaceElevated,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

// ── LumiInfoRow ────────────────────────────────────────────────────────────────

@Composable
fun LumiInfoRow(
    badge: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LumiSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LumiInk)
            Text(subtitle, fontSize = 16.sp, color = LumiMuted, lineHeight = 22.sp)
        }
    }
}

// ── LanguageChip ──────────────────────────────────────────────────────────────

data class LanguageOption(val code: String, val label: String, val nativeLabel: String)

val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("hi", "Hindi", "हिंदी"),
    LanguageOption("en", "English", "English"),
    LanguageOption("mr", "Marathi", "मराठी"),
    LanguageOption("ta", "Tamil", "தமிழ்"),
    LanguageOption("bn", "Bengali", "বাংলা"),
    LanguageOption("te", "Telugu", "తెలుగు")
)

@Composable
fun LumiLanguageGrid(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SUPPORTED_LANGUAGES.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { lang ->
                    LanguageChip(
                        option = lang,
                        isSelected = selected.contains(lang.code),
                        onSelect = { onToggle(lang.code) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun LumiLanguageGrid(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LumiLanguageGrid(
        selected = if (selected != null) setOf(selected) else emptySet(),
        onToggle = onSelect,
        modifier = modifier
    )
}

@Composable
private fun LanguageChip(
    option: LanguageOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) LumiSaffronDim else LumiSurface
    val borderColor = if (isSelected) LumiSaffron else LumiBorder
    val contentColor = if (isSelected) LumiSaffron else Color(0xFFCCCCDD)

    OutlinedButton(
        onClick = onSelect,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Text("✓ ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LumiSaffron)
                }
                Text(option.nativeLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(option.label, fontSize = 16.sp, color = LumiMuted)
        }
    }
}

// ── PermissionRow ─────────────────────────────────────────────────────────────

@Composable
fun PermissionRow(label: String, isGranted: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 16.sp, color = LumiInk, modifier = Modifier.weight(1f))
        if (isGranted) {
            Text("✓", color = SuccessGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = LumiSaffron
            )
        }
    }
}
