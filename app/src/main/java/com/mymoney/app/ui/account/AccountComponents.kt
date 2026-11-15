package com.mymoney.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

/** Google profile photo, falling back to the first initial (offline / no photo). */
@Composable
fun Avatar(photoUrl: String?, name: String?, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    val initial: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(name?.trim()?.firstOrNull()?.uppercase() ?: "M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
    if (photoUrl.isNullOrBlank()) Box(modifier) { initial() }
    else SubcomposeAsyncImage(
        model = photoUrl, contentDescription = "Profile photo", contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
        loading = { initial() }, error = { initial() },
    )
}

enum class LegalDoc(val title: String) { PRIVACY("Privacy Policy"), TERMS("Terms of Use") }

/** "Privacy Policy · Terms" links that open the documents in a dialog (works offline). */
@Composable
fun LegalLinks(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<LegalDoc?>(null) }
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = LegalDoc.PRIVACY }) { Text("Privacy Policy", style = MaterialTheme.typography.labelMedium) }
        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open = LegalDoc.TERMS }) { Text("Terms of Use", style = MaterialTheme.typography.labelMedium) }
    }
    open?.let { LegalDialog(it) { open = null } }
}

@Composable
fun LegalDialog(doc: LegalDoc, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (doc == LegalDoc.PRIVACY) PRIVACY_TEXT else TERMS_TEXT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val PRIVACY_TEXT = """
FinFox is an offline-first personal finance tracker. This policy describes exactly what the app does with your data.

1. Your financial data stays on your device
Accounts, transactions, scheduled payments, lending/borrowing, budgets and goals are stored only in a local database on this device. FinFox has no server of its own and never sends your financial records anywhere unless you create a backup.

2. Google Sign-In (optional)
If you choose "Continue with Google", Google's account picker and Firebase Authentication verify your identity. The app keeps only your Google display name, email address, profile photo link and account id locally. It never sees or stores your Google password and does not store Google access tokens on disk.

3. Google Drive backup (optional, separate permission)
Signing in does not give access to your Drive. Only if you connect Google Drive does the app request the "app data" scope, which allows it to see only files it created in a hidden app folder of your own Drive — not your other files. Backups are encrypted on your device with AES-256-GCM using a key derived (Argon2id) from your recovery passphrase before upload. Neither the passphrase nor the key is uploaded; Google cannot read your backups and nor can we. If you lose the passphrase, the backup cannot be recovered.

4. What backups contain
Financial records and app preferences. They never contain your PIN, biometric data, Google credentials or tokens, or the encryption key.

5. Notifications and widgets
Reminders are generated on your device. You can hide amounts on the lock screen and on home screen widgets (privacy mode). Notification history is stored only locally.

6. Security
Your PIN is stored as a salted hash protected by the Android Keystore. Android's own cloud backup of app data is disabled.

7. Your choices
You can use the app fully offline, sign out at any time (your local data is kept), disconnect Google Drive, revoke the app's access, delete backups from Backup History, or erase all data from Settings.

8. Children
FinFox is not directed at children under 13.

9. Changes
Material changes to this policy will be shown in the app.
""".trimIndent()

private val TERMS_TEXT = """
By using FinFox you agree to the following.

1. A tracking tool, not a bank
FinFox records money you tell it about. It never moves real money, connects to bank accounts, initiates payments or asks for card numbers, CVVs, UPI PINs or banking passwords. "Mark as paid" only records a payment you made elsewhere.

2. Your responsibility
You are responsible for the accuracy of the data you enter and for decisions you make based on it. The app's calculations are provided as-is for personal record keeping and are not financial, tax or legal advice.

3. Backups and passphrases
Cloud backups are stored in your own Google Drive and encrypted with your recovery passphrase. Keep the passphrase safe: it cannot be reset or recovered by anyone, and without it an encrypted backup cannot be restored. Keep your recovery code for your PIN in a safe place too.

4. Google services
Use of Google Sign-In and Google Drive is also subject to Google's terms. You can disconnect them at any time from Settings.

5. Availability
Background tasks such as automatic backups and reminders are scheduled by Android and may run later than the chosen time to save battery; exact timing is not guaranteed.

6. Liability
To the extent permitted by law, the app is provided without warranty and the authors are not liable for losses arising from its use, including lost data where no backup exists.
""".trimIndent()
