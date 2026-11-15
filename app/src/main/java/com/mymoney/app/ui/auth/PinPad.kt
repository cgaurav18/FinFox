package com.mymoney.app.ui.auth

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PinDots(length: Int, filled: Int, error: Boolean, color: Color = MaterialTheme.colorScheme.primary) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { contentDescription = "$filled of $length digits entered" }) {
        repeat(length) { i ->
            val size by animateDpAsState(if (i < filled) 16.dp else 14.dp, label = "dot")
            val c = if (error) MaterialTheme.colorScheme.error else color
            Box(
                Modifier.size(size).clip(CircleShape)
                    .background(if (i < filled) c else Color.Transparent)
                    .border(2.dp, c, CircleShape)
            )
        }
    }
}

@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { r.forEach { d -> Key(d.toString(), enabled, contentColor) { onDigit(d) } } } }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (onBiometric != null) KeyIcon(enabled, contentColor, "Use fingerprint", onBiometric) { Icon(Icons.Filled.Fingerprint, null, tint = contentColor, modifier = Modifier.size(30.dp)) }
            else Box(Modifier.size(72.dp))
            Key("0", enabled, contentColor) { onDigit('0') }
            KeyIcon(enabled, contentColor, "Delete", onBackspace) { Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = contentColor) }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(color.copy(alpha = 0.07f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 28.sp, fontWeight = FontWeight.Medium, color = color) }
}

@Composable
private fun KeyIcon(enabled: Boolean, color: Color, desc: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { content() }
}
