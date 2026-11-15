package com.mymoney.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.ui.account.Avatar
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

@Composable
fun DriveConnectionScreen(nav: NavController, vm: CloudBackupViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var disconnect by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf(false) }
    DriveConsentLauncher(vm)
    AppScaffold(title = "Google Drive", onBack = { nav.popBackStack() }) { pad ->
        if (!s.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val p = s.profile
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(if (p.signedIn) p.photoUrl else null, p.displayName, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (p.signedIn) p.email ?: "Google account" else "Not signed in", style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            !p.signedIn -> "Sign in with Google first"
                            p.driveConnected && p.driveNeedsReauth -> "Permission expired — reconnect"
                            p.driveConnected -> "Connected ${p.driveConnectedAt?.let { "on " + Dates.formatDay(it) } ?: ""}"
                            else -> "Google Drive not connected"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        p.driveConnected && !p.driveNeedsReauth -> Chip("Connected", FinanceTheme.colors.income)
                        p.driveNeedsReauth -> Chip("Action needed", MaterialTheme.colorScheme.error)
                        else -> Chip("Off", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionHeader("What My Money can access")
            AppCard {
                Point(Icons.Filled.FolderOff, "Only its own hidden folder", "The drive.appdata permission lets My Money see only the backup files it creates in a private app folder — not your photos, documents or any other Drive files.")
                Point(Icons.Filled.Lock, "Encrypted before upload", "Backups are encrypted on this phone with AES-256-GCM. Google stores only unreadable data.")
                Point(Icons.Filled.Key, "Your passphrase, your key", "The key comes from your recovery passphrase (Argon2id) and is never uploaded.")
            }
            when {
                !p.signedIn -> PrimaryButton("Sign in with Google", { nav.navigate(Routes.GOOGLE_ACCOUNT) }, icon = Icons.Filled.Login)
                !p.driveConnected || p.driveNeedsReauth -> PrimaryButton(if (p.driveNeedsReauth) "Reconnect Google Drive" else "Connect Google Drive", vm::connectDrive, loading = busy, icon = Icons.Filled.AddToDrive)
                else -> {
                    if (!s.hasPassphrase) PrimaryButton("Set recovery passphrase", { passphrase = true }, icon = Icons.Filled.Key)
                    else PrimaryButton("Go to Backup & Restore", { nav.navigate(Routes.BACKUP) { launchSingleTop = true } }, icon = Icons.Filled.CheckCircle)
                    SecondaryButton("Disconnect Google Drive", { disconnect = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.CloudOff, destructive = true)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (passphrase) PassphraseDialog(vm, changing = false) { passphrase = false }
    if (disconnect) ConfirmDialog(
        "Disconnect Google Drive?", "Automatic backups stop and My Money's Drive permission is revoked. Backups already in your Drive are kept and can be restored after reconnecting.",
        "Disconnect", onConfirm = vm::disconnectDrive, onDismiss = { disconnect = false },
    )
}

@Composable
private fun Point(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
