package com.mymoney.app.ui.account

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.ui.auth.Biometrics
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

@Composable
fun GoogleAccountScreen(nav: NavController, vm: AccountViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val signingIn by vm.signingIn.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember { Biometrics.findActivity(context) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val p = s.profile
    AppScaffold(title = "Google Account", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(if (p.signedIn) p.photoUrl else null, p.displayName ?: "M", 84.dp)
                    Spacer(Modifier.height(12.dp))
                    if (p.signedIn) {
                        Text(p.displayName ?: "Google user", style = MaterialTheme.typography.titleLarge)
                        Text(p.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Chip("Connected", FinFoxTheme.colors.income)
                    } else {
                        Text("Not signed in", style = MaterialTheme.typography.titleLarge)
                        Text("You're using FinFox offline. Sign in to back up to your own Google Drive.", textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (p.signedIn) {
                SectionHeader("Status")
                AppCard {
                    KeyValueRow("Google account", "Connected")
                    KeyValueRow("Signed in", p.signedInAt?.let { "${Dates.formatDay(it)} ${Dates.formatTime(it)}" } ?: "-")
                    KeyValueRow("Google Drive", when {
                        p.driveConnected && p.driveNeedsReauth -> "Needs reconnection"
                        p.driveConnected -> "Connected (app folder only)"
                        else -> "Not connected"
                    }, if (p.driveNeedsReauth) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    KeyValueRow("Last backup", s.backup.lastSuccessAt?.let { "${Dates.formatDay(it)} ${Dates.formatTime(it)}" } ?: "Never")
                }
                SecondaryButton(if (p.driveConnected) "Backup & Restore" else "Connect Google Drive", { nav.navigate(if (p.driveConnected) Routes.BACKUP else Routes.DRIVE) },
                    Modifier.fillMaxWidth(), icon = if (p.driveConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff)
                SectionHeader("Manage")
                SecondaryButton("Switch account", { activity?.let(vm::switchAccount) }, Modifier.fillMaxWidth(), icon = Icons.Filled.SwitchAccount, enabled = !signingIn)
                SecondaryButton("Sign out", { confirmSignOut = true }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.Logout, enabled = !busy)
                SecondaryButton("Revoke access", { confirmRevoke = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Block, destructive = true, enabled = !busy)
                Text("Signing out or revoking access never deletes the data on this device or your existing Drive backups.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PrimaryButton("Continue with Google", { activity?.let { vm.signIn(it) } }, loading = signingIn, enabled = s.configured, icon = Icons.Filled.Login)
                if (!s.configured) Text("Google Sign-In isn't configured in this build (missing OAuth client / Firebase config). See the README.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(4.dp))
                    Text("Signing in only shares your name, email and photo with FinFox. Google Drive access is requested separately, only if you turn on cloud backup.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LegalLinks(Modifier.fillMaxWidth())
        }
    }
    if (confirmSignOut) ConfirmDialog(
        "Sign out of Google?", "Your financial data stays on this device. Automatic Drive backups will stop until you sign in again.",
        "Sign out", onConfirm = vm::signOut, onDismiss = { confirmSignOut = false },
    )
    if (confirmRevoke) ConfirmDialog(
        "Revoke access?", "FinFox's permission to your Google Drive app folder will be revoked and you'll be signed out. Data on this device and backups already in Drive are kept.",
        "Revoke", onConfirm = vm::revokeAccess, onDismiss = { confirmRevoke = false },
    )
}
