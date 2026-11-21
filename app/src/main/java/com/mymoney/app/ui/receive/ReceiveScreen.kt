package com.mymoney.app.ui.receive

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mymoney.app.core.Money
import com.mymoney.app.data.repo.MyUpiId
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton

/** "Receive money": a UPI QR for one of the user's own UPI IDs, optionally for a fixed amount. */
@Composable
fun ReceiveScreen(nav: NavController, vm: ReceiveViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MyUpiId?>(null) }
    val clipboard = LocalClipboardManager.current
    AppScaffold(title = "Receive money", onBack = { nav.popBackStack() }) { pad ->
        if (s.loading) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sel = s.selected
            if (sel == null) {
                AppCard {
                    EmptyState(Icons.Filled.QrCode2, "Show a QR to get paid", "Save your UPI ID once. FinFox turns it into a QR code anyone can scan with any UPI app.",
                        action = "Add your UPI ID") { adding = true }
                }
                return@Column
            }
            if (s.ids.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.ids.forEach { id -> FilterChip(selected = id.vpa == sel.vpa, onClick = { vm.select(id.vpa) }, label = { Text(id.label.ifBlank { id.vpa }) }) }
            }
            QrCard(sel, s.amountMinor, s.note, s.qrPayload.orEmpty()) { clipboard.setText(AnnotatedString(sel.vpa)) }
            AppCard {
                Text("Ask for a specific amount (optional)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                AmountField(s.amount, { vm.amount.value = it }, label = "Amount")
                Spacer(Modifier.height(8.dp))
                AppTextField(s.note, { vm.note.value = it }, "Note for the payer", maxLength = 50)
            }
            SectionHeader("Your UPI IDs", action = "Add") { adding = true }
            AppCard(contentPadding = 0.dp) {
                s.ids.forEachIndexed { i, id ->
                    ListRow(Icons.Filled.AlternateEmail, MaterialTheme.colorScheme.primary, id.label.ifBlank { id.name }, id.vpa, onClick = { vm.select(id.vpa) }) {
                        if (i == 0) Chip("Default", MaterialTheme.colorScheme.primary)
                        else IconButton(onClick = { vm.makeDefault(id.vpa) }) { Icon(Icons.Filled.StarOutline, "Make default") }
                        IconButton(onClick = { removing = id }) { Icon(Icons.Filled.Delete, "Remove ${id.vpa}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (i < s.ids.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    if (adding) AddUpiIdDialog(onDismiss = { adding = false }) { vpa, name, label -> vm.add(vpa, name, label) { adding = false } }
    removing?.let { id ->
        ConfirmDialog("Remove ${id.vpa}?", "Its QR code won't be shown in FinFox any more. Your UPI account isn't affected.", "Remove",
            onConfirm = { vm.remove(id.vpa) }, onDismiss = { removing = null })
    }
}

@Composable
private fun QrCard(id: MyUpiId, amountMinor: Long?, note: String, payload: String, onCopy: () -> Unit) {
    val qr = remember(payload) { qrBitmap(payload, 720) }
    AppCard(contentPadding = 20.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(id.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (amountMinor != null) {
                Spacer(Modifier.height(2.dp))
                Text("Requesting " + Money.format(amountMinor), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            // Always black on white, also in dark mode: that is what scanners read best.
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                Image(qr, contentDescription = "UPI QR code for ${id.vpa}", modifier = Modifier.size(240.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(id.vpa, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy UPI ID", tint = MaterialTheme.colorScheme.primary) }
            }
            Text("Scan with any UPI app to pay" + (note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AddUpiIdDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var vpa by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add your UPI ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(vpa, { vpa = it.trim() }, "UPI ID", leadingIcon = Icons.Filled.AlternateEmail, placeholder = "yourname@okaxis",
                    keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, maxLength = 100)
                AppTextField(name, { name = it }, "Name payers see", leadingIcon = Icons.Filled.Person, capitalization = KeyboardCapitalization.Words, maxLength = 50)
                AppTextField(label, { label = it }, "Label (optional)", leadingIcon = Icons.Filled.Label, placeholder = "e.g. Kotak", capitalization = KeyboardCapitalization.Words, maxLength = 30)
                Text("Find your UPI ID in your UPI app's profile. Use the one linked to the bank account you want money in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(vpa, name, label) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Renders [text] as a QR code (black on white, quiet zone of one module). */
internal fun qrBitmap(text: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
