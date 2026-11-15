package com.mymoney.app.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.theme.FinFoxTheme
import kotlinx.coroutines.launch

/** Launches Google's Drive consent screen whenever the view model asks for it. */
@Composable
fun DriveConsentLauncher(vm: CloudBackupViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> vm.onConsentResult(r.resultCode, r.data) }
    LaunchedEffect(Unit) {
        vm.consentRequests.collect { pi -> launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
    }
}

/** Create / change the cloud recovery passphrase, with an explicit "can't be recovered" acknowledgement. */
@Composable
fun PassphraseDialog(vm: CloudBackupViewModel, changing: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var ack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val strength = CloudBackupViewModel.passphraseStrength(p)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (changing) "Change recovery passphrase" else "Create recovery passphrase") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backups are encrypted on this phone with a key made from this passphrase. You'll need it to restore on a new phone. " +
                    "It's never uploaded and nobody — not Google, not us — can reset it.", style = MaterialTheme.typography.bodySmall)
                if (changing) Text("Backups made earlier still need the passphrase they were created with.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                AppTextField(p, { p = it; error = null }, "Passphrase (min ${BackupCrypto.MIN_PASSPHRASE} characters)", keyboardType = KeyboardType.Password, maxLength = 128)
                if (p.isNotEmpty()) {
                    val (label, color) = when (strength) {
                        0, 1 -> "Weak" to MaterialTheme.colorScheme.error
                        2 -> "Fair" to FinFoxTheme.colors.warning
                        3 -> "Good" to FinFoxTheme.colors.income
                        else -> "Strong" to FinFoxTheme.colors.income
                    }
                    ProgressBar(strength / 4f, color, height = 6.dp)
                    Text("Strength: $label", style = MaterialTheme.typography.labelSmall, color = color)
                }
                AppTextField(c, { c = it; error = null }, "Confirm passphrase", keyboardType = KeyboardType.Password, maxLength = 128, error = error)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(ack, { ack = it })
                    Text("I understand that if I lose this passphrase, my backups can't be recovered.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                scope.launch {
                    saving = true
                    error = vm.setPassphrase(p, c, ack)
                    saving = false
                    if (error == null) onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}
