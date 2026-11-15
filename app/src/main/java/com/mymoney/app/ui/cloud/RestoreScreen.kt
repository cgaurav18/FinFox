package com.mymoney.app.ui.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.backup.BackupPayload
import com.mymoney.app.backup.RestoreMode
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

@Composable
fun RestoreScreen(nav: NavController, vm: RestoreViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val working = s.step == RestoreStep.RESTORING || s.step == RestoreStep.DECRYPTING
    AppScaffold(title = "Restore Backup", onBack = { if (!working) nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s.step) {
                RestoreStep.LOADING -> Busy("Downloading backup from ${s.source}…")
                RestoreStep.DECRYPTING -> Busy("Verifying and decrypting…")
                RestoreStep.RESTORING -> Busy("Restoring your data safely…")
                RestoreStep.ERROR -> {
                    Message(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "Couldn't open this backup", s.error ?: "")
                    PrimaryButton("Try again", { vm.load() })
                    SecondaryButton("Back", { nav.popBackStack() }, Modifier.fillMaxWidth())
                }
                RestoreStep.PASSPHRASE -> PassphraseStep(s, vm::decrypt)
                RestoreStep.PREVIEW -> PreviewStep(s, vm::restore) { nav.popBackStack() }
                RestoreStep.DONE -> {
                    val r = s.report!!
                    Message(Icons.Filled.CheckCircle, FinanceTheme.colors.income, "Restore complete",
                        if (r.mode == RestoreMode.REPLACE) "Your data now matches the backup." else "${r.totalInserted} records added, ${r.totalSkipped} duplicates skipped.")
                    AppCard {
                        r.inserted.forEach { (k, v) -> KeyValueRow(k, if (r.mode == RestoreMode.MERGE) "+$v" else "$v") }
                        if (r.duplicatesSkipped.isNotEmpty()) KeyValueRow("Duplicates skipped", r.totalSkipped.toString())
                        KeyValueRow("Active accounts", r.accountsAfter.toString())
                        KeyValueRow("Available balance", Money.format(r.availableBalanceMinor, LocalCurrency.current))
                    }
                    Text("Balances were recalculated from the restored ledger. The dashboard and home screen widgets have been refreshed. You can undo this restore from Backup & Restore.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton("Go to dashboard", { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false }; launchSingleTop = true } })
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.height(56.dp).width(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PassphraseStep(s: RestoreState, onDecrypt: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    s.remote?.let { b ->
        AppCard {
            KeyValueRow("Backup from", "${Dates.formatDay(b.createdAt)}, ${Dates.formatTime(b.createdAt)}")
            KeyValueRow("Size", BackupWorker.formatSize(b.sizeBytes))
            b.device?.let { KeyValueRow("Device", it) }
        }
    }
    Message(Icons.Filled.Key, MaterialTheme.colorScheme.primary, "Enter your recovery passphrase",
        if (s.remote != null) "The passphrase you set when this backup was created. It's checked on this phone; nothing is sent anywhere."
        else "The password used when this backup file was created.")
    AppTextField(pass, { pass = it }, if (s.remote != null) "Recovery passphrase" else "Backup password", keyboardType = KeyboardType.Password, maxLength = 128, error = s.error)
    PrimaryButton("Decrypt backup", { onDecrypt(pass) }, enabled = pass.isNotEmpty())
    Text("Lost the passphrase? Encrypted backups can't be opened without it — this is what keeps them private.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PreviewStep(s: RestoreState, onRestore: (RestoreMode) -> Unit, onCancel: () -> Unit) {
    val p: BackupPayload = s.payload!!
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var typed by remember { mutableStateOf("") }
    SectionHeader("Backup contents")
    AppCard {
        KeyValueRow("Created", "${Dates.formatDay(p.exportedAt)}, ${Dates.formatTime(p.exportedAt)}")
        KeyValueRow("Format", "v${p.formatVersion} (database v${p.schemaVersion}) — verified")
        KeyValueRow("Accounts", p.accounts.size.toString())
        KeyValueRow("Transactions", p.transactions.size.toString())
        KeyValueRow("Scheduled", p.schedules.size.toString())
        KeyValueRow("Lending/borrowing", "${p.loans.size} entries, ${p.repayments.size} repayments")
        KeyValueRow("Budgets & goals", "${p.budgets.size} budgets, ${p.goals.size} goals")
    }
    SectionHeader("On this phone now")
    AppCard {
        KeyValueRow("Accounts", s.currentAccounts.toString())
        KeyValueRow("Transactions", s.currentTransactions.toString())
    }
    SectionHeader("How to restore")
    ModeOption(mode == RestoreMode.MERGE, "Merge (recommended)", "Adds records from the backup that aren't already here. Existing records are never changed; duplicates are skipped.") { mode = RestoreMode.MERGE }
    ModeOption(mode == RestoreMode.REPLACE, "Replace everything", "Deletes all current data on this phone and replaces it with the backup.") { mode = RestoreMode.REPLACE }
    if (mode == RestoreMode.REPLACE && (s.currentTransactions > 0 || s.currentAccounts > 0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("${s.currentAccounts} accounts and ${s.currentTransactions} transactions on this phone will be replaced. A safety copy is kept so you can undo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        AppTextField(typed, { typed = it }, "Type REPLACE to confirm", capitalization = KeyboardCapitalization.Characters, maxLength = 10)
    }
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    PrimaryButton(if (mode == RestoreMode.MERGE) "Merge into my data" else "Replace my data", { onRestore(mode) },
        enabled = mode == RestoreMode.MERGE || typed.trim() == "REPLACE" || (s.currentTransactions == 0 && s.currentAccounts == 0), icon = Icons.Filled.Restore)
    SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
}

@Composable
private fun ModeOption(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
