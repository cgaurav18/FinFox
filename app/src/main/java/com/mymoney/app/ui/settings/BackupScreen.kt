package com.mymoney.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.core.Dates
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(nav: NavController, vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { vm.export(uri, password); password = ""; confirm = "" }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::exportPdf) }
    val stamp = remember { Dates.today().format(DateTimeFormatter.BASIC_ISO_DATE) }

    AppScaffold(title = "Backup & Restore", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Encrypted backup")
            AppCard {
                Text("Saves all your data to a file you choose (e.g. Downloads or Google Drive via the file picker). The file is encrypted with AES-256 using your password — without it, the backup can't be read. The password is never stored.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                AppTextField(password, { password = it; error = null }, "Backup password (min ${BackupCrypto.MIN_PASSWORD} characters)", keyboardType = KeyboardType.Password, maxLength = 64)
                Spacer(Modifier.height(8.dp))
                AppTextField(confirm, { confirm = it; error = null }, "Confirm password", keyboardType = KeyboardType.Password, maxLength = 64, error = error)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Create backup", {
                    error = when {
                        password.length < BackupCrypto.MIN_PASSWORD -> "Use at least ${BackupCrypto.MIN_PASSWORD} characters"
                        password != confirm -> "Passwords don't match"
                        else -> null
                    }
                    if (error == null) { vm.beforeExternalActivity(); exportLauncher.launch("MyMoney_backup_$stamp.mmbk") }
                }, loading = busy, icon = Icons.Filled.Backup)
            }
            SectionHeader("Restore")
            AppCard {
                Text("Restoring replaces ALL current data with the backup. You'll be asked to confirm after the file is decrypted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Choose backup file", { vm.beforeExternalActivity(); openLauncher.launch(arrayOf("*/*")) }, icon = Icons.Filled.Restore)
            }
            SectionHeader("Export")
            AppCard {
                Text("Export all transactions for spreadsheets or printing. Exports are not encrypted. Use Reports to export a specific period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("CSV", { vm.beforeExternalActivity(); csv.launch("MyMoney_transactions_$stamp.csv") }, Modifier.weight(1f), icon = Icons.Filled.Description)
                    TonalButton("PDF", { vm.beforeExternalActivity(); pdf.launch("MyMoney_report_$stamp.pdf") }, Modifier.weight(1f), icon = Icons.Filled.PictureAsPdf)
                }
            }
        }
    }
    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Enter backup password") },
            text = { AppTextField(restorePassword, { restorePassword = it }, "Password", keyboardType = KeyboardType.Password, maxLength = 64) },
            confirmButton = { TextButton(onClick = { vm.read(uri, restorePassword); restorePassword = ""; restoreUri = null }) { Text("Decrypt") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Replace all data?") },
            text = {
                Text("Backup from ${Dates.formatDay(p.exportedAt)} ${Dates.formatTime(p.exportedAt)} contains ${p.accounts.size} accounts, ${p.transactions.size} transactions, ${p.schedules.size} schedules, ${p.loans.size} loan entries, ${p.budgets.size} budgets and ${p.goals.size} goals.\n\nEverything currently in the app will be replaced. This can't be undone.")
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Restore", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
}
