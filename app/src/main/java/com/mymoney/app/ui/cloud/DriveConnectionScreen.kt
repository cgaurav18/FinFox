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
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.foundation.layout.size
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

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
                    androidx.compose.material3.Icon(Icons.Filled.AddToDrive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (p.driveConnected) p.email ?: "Google Drive" else "Google Drive", style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            p.driveConnected && p.driveNeedsReauth -> "Permission expired — reconnect"
                            p.driveConnected -> "Connected ${p.driveConnectedAt?.let { "on " + Dates.formatDay(it) } ?: ""}"
                            else -> "Google Drive not connected"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        p.driveConnected && !p.driveNeedsReauth -> Chip("Connected", FinFoxTheme.colors.income)
                        p.driveNeedsReauth -> Chip("Action needed", MaterialTheme.colorScheme.error)
                        else -> Chip("Off", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val setup by vm.driveSetupProblem.collectAsStateWithLifecycle()
            if (setup) DriveSetupCard()
            Text("Tap Connect and choose the Google account whose Drive should hold your backups. No FinFox account or sign-in is needed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("What FinFox can access")
            AppCard {
                Point(Icons.Filled.FolderOff, "Only its own hidden folder", "The drive.appdata permission lets FinFox see only the backup files it creates in a private app folder — not your photos, documents or any other Drive files.")
                Point(Icons.Filled.Lock, "Encrypted before upload", "Backups are encrypted on this phone with AES-256-GCM. Google stores only unreadable data.")
                Point(Icons.Filled.Key, "Your passphrase, your key", "The key comes from your recovery passphrase (Argon2id) and is never uploaded.")
            }
            when {
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
        "Disconnect Google Drive?", "Automatic backups stop and FinFox's Drive permission is revoked. Backups already in your Drive are kept and can be restored after reconnecting.",
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

/**
 * Shown when Google rejects the build (DEVELOPER_ERROR). Drive access needs an Android OAuth client registered in
 * Google Cloud for this exact package name and signing SHA-1; the values are read from the running app.
 */
@Composable
private fun DriveSetupCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val sha1 = remember { com.mymoney.app.cloud.signingSha1(context) ?: "unknown" }
    AppCard {
        Text("Google Drive isn't set up for this build", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        Text("Google only lets registered apps use Drive. Register this build once in Google Cloud Console (free):",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        listOf(
            "1. console.cloud.google.com → create a project",
            "2. APIs & Services → Library → enable \"Google Drive API\"",
            "3. OAuth consent screen → External → add your Gmail under Test users",
            "4. Credentials → Create credentials → OAuth client ID → Android, with the package name and SHA-1 below",
            "5. Wait a few minutes, then tap Connect again",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        SetupValue("Package name", context.packageName) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(context.packageName)) }
        SetupValue("SHA-1", sha1) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(sha1)) }
    }
}

@Composable
private fun SetupValue(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        }
        androidx.compose.material3.IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy $label", tint = MaterialTheme.colorScheme.primary) }
    }
}
