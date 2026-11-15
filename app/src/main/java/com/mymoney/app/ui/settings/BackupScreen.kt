package com.mymoney.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.BackupFrequency
import com.mymoney.app.ui.account.Avatar
import com.mymoney.app.ui.cloud.CloudBackupViewModel
import com.mymoney.app.ui.cloud.DriveConsentLauncher
import com.mymoney.app.ui.cloud.PassphraseDialog
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(nav: NavController, vm: BackupViewModel = hiltViewModel(), cloudVm: CloudBackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s by cloudVm.state.collectAsStateWithLifecycle()
    val cloudBusy by cloudVm.busy.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var passphraseDialog by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf(false) }
    DriveConsentLauncher(cloudVm)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { vm.export(uri, password); password = ""; confirm = "" }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) nav.navigate(Routes.restoreFile(uri.toString()))
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::exportPdf) }
    val stamp = remember { Dates.today().format(DateTimeFormatter.BASIC_ISO_DATE) }
    val p = s.profile

    AppScaffold(title = "Backup & Restore", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Google Drive backup")
            AppCard(contentPadding = 0.dp) {
                ListRow(Icons.Filled.CloudUpload, MaterialTheme.colorScheme.primary,
                    if (p.signedIn) p.email ?: "Google account" else "Not signed in",
                    when {
                        !p.signedIn -> "Sign in with Google to back up to your Drive"
                        p.driveNeedsReauth -> "Google Drive permission expired — tap to reconnect"
                        p.driveConnected -> "Google Drive connected (app folder only)"
                        else -> "Google Drive not connected — tap to connect"
                    },
                    onClick = { nav.navigate(if (p.signedIn) Routes.DRIVE else Routes.GOOGLE_ACCOUNT) },
                ) {
                    if (p.signedIn) Avatar(p.photoUrl, p.displayName, 30.dp)
                    else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.driveNeedsReauth) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Automatic backups are paused until you reconnect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TonalButton("Reconnect", cloudVm::connectDrive)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val last = s.lastSuccess
                    KeyValueRow("Status", when {
                        !p.signedIn || !p.driveConnected -> "Not connected"
                        p.driveNeedsReauth -> "Needs reconnection"
                        s.backupRunning -> "Backing up…"
                        else -> "Ready"
                    })
                    KeyValueRow("Last backup", last?.let { "${Dates.formatDay(it.createdAt)}, ${Dates.formatTime(it.createdAt)}" } ?: "Never")
                    KeyValueRow("Backup size", last?.let { BackupWorker.formatSize(it.sizeBytes) } ?: "-")
                    s.prefs.lastError?.takeIf { s.prefs.lastAttemptAt != null && (last == null || s.prefs.lastAttemptAt!! > last.createdAt) }?.let {
                        KeyValueRow("Last attempt", "Failed: $it", MaterialTheme.colorScheme.error)
                    }
                    if (s.backupRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }

            AppCard(contentPadding = 0.dp) {
                ListRow(Icons.Filled.Key, Color(0xFFF59E0B), if (s.hasPassphrase) "Recovery passphrase set" else "Set a recovery passphrase",
                    if (s.hasPassphrase) "Needed to restore on another phone. Tap to change." else "Required before your first Drive backup",
                    onClick = { passphraseDialog = true }) {
                    if (s.hasPassphrase) Chip("Set", FinanceTheme.colors.income) else Chip("Required", MaterialTheme.colorScheme.error)
                }
            }

            AppCard {
                LabeledSwitchRow("Automatic backup", "Back up in the background when connected", s.prefs.autoBackupEnabled && s.prefs.frequency != BackupFrequency.MANUAL,
                    cloudVm::setAuto, enabled = s.ready)
                DropdownField("Backup frequency", BackupFrequency.entries, s.prefs.frequency, { it.label }, cloudVm::setFrequency, Modifier.padding(top = 4.dp))
                LabeledSwitchRow("Wi-Fi only", "Don't use mobile data for automatic backups", s.prefs.wifiOnly, cloudVm::setWifiOnly)
                DropdownField("Keep in Drive", listOf(3, 5, 10, 20, 30), s.prefs.keepCount, { "Latest $it backups" }, cloudVm::setKeep)
                Spacer(Modifier.height(6.dp))
                Text("Android runs background backups when the phone is idle, charged enough and online, so the exact time can vary.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PrimaryButton("Backup Now", cloudVm::backupNow, loading = s.backupRunning || cloudBusy, icon = Icons.Filled.Backup)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Restore Backup", { nav.navigate(Routes.BACKUP_HISTORY) }, Modifier.weight(1f), icon = Icons.Filled.Restore, enabled = p.signedIn && p.driveConnected)
                SecondaryButton("History", { nav.navigate(Routes.BACKUP_HISTORY) }, Modifier.weight(1f), icon = Icons.Filled.History)
            }
            if (p.driveConnected) SecondaryButton("Disconnect Google Drive", { disconnect = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.CloudOff, destructive = true)
            s.safetyCopyAt?.let { at ->
                AppCard {
                    Text("Undo last restore", style = MaterialTheme.typography.titleSmall)
                    Text("A safety copy of your data from before the restore on ${Dates.formatDay(at)} ${Dates.formatTime(at)} is kept on this device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TonalButton("Restore previous data", { undo = true }, icon = Icons.Filled.Undo)
                }
            }

            SectionHeader("Backup to a file")
            AppCard {
                Text("Saves an encrypted copy to a file you choose (e.g. Downloads). It's encrypted with AES-256 using your password — without it the backup can't be read. The password is never stored.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                AppTextField(password, { password = it; error = null }, "Backup password (min ${BackupCrypto.MIN_PASSWORD} characters)", keyboardType = KeyboardType.Password, maxLength = 64)
                Spacer(Modifier.height(8.dp))
                AppTextField(confirm, { confirm = it; error = null }, "Confirm password", keyboardType = KeyboardType.Password, maxLength = 64, error = error)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Create backup file", {
                    error = when {
                        password.length < BackupCrypto.MIN_PASSWORD -> "Use at least ${BackupCrypto.MIN_PASSWORD} characters"
                        password != confirm -> "Passwords don't match"
                        else -> null
                    }
                    if (error == null) { vm.beforeExternalActivity(); exportLauncher.launch("MyMoney_backup_$stamp.mmbk") }
                }, loading = busy, icon = Icons.Filled.Backup)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Restore from a file", { vm.beforeExternalActivity(); openLauncher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Restore)
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
    if (passphraseDialog) PassphraseDialog(cloudVm, changing = s.hasPassphrase) { passphraseDialog = false }
    if (disconnect) ConfirmDialog(
        "Disconnect Google Drive?", "Automatic backups stop and My Money's Drive permission is revoked. Backups already in your Drive are kept.",
        "Disconnect", onConfirm = cloudVm::disconnectDrive, onDismiss = { disconnect = false },
    )
    if (undo) ConfirmDialog(
        "Restore previous data?", "Everything changed by the last restore — and anything added since — will be replaced by the safety copy taken just before that restore.",
        "Restore previous data", onConfirm = cloudVm::undoLastRestore, onDismiss = { undo = false },
    )
}
