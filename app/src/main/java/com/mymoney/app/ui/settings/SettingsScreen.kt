package com.mymoney.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import com.mymoney.app.ui.theme.FinFoxTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.BuildConfig
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.ThemeMode
import com.mymoney.app.security.PinManager
import com.mymoney.app.ui.auth.Biometrics
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val sec by vm.security.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val fin = FinFoxTheme.colors
    val backupPrefs by vm.backupPrefs.collectAsStateWithLifecycle()
    val widgetPrefs by vm.widgetPrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    AppScaffold(title = "Settings", onBack = { nav.popBackStack() }) { pad ->
        val st = s ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val primary = MaterialTheme.colorScheme.primary
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionHeader("Profile") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Person, primary, "Name", st.profileName.ifBlank { "Not set" }) { editName = true }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        DropdownField("Currency", Money.supportedCurrencies, st.currency, { "$it (${Money.symbolFor(it).trim()})" }, { c -> vm.update { it.copy(currency = c) } },
                            leading = { Icon(Icons.Filled.CurrencyRupee, null) })
                    }
                }
            }
            item { SectionHeader("Appearance") }
            item {
                AppCard(contentPadding = 0.dp) {
                    listOf(
                        Triple(ThemeMode.LIGHT, "Light Mode", "Bright background with white cards"),
                        Triple(ThemeMode.DARK, "Dark Mode", "Deep purple background, easy on the eyes"),
                        Triple(ThemeMode.SYSTEM, "Follow System", "Match your phone's theme setting"),
                    ).forEach { (mode, title, sub) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = themeMode == mode, role = Role.RadioButton) { vm.setTheme(mode) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = themeMode == mode, onClick = null)
                        }
                    }
                }
            }
            item { SectionHeader("Cloud Backup") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.AddToDrive, fin.savings, if (profile.driveConnected) "Google Drive connected" else "Connect Google Drive",
                        when {
                            profile.driveNeedsReauth -> "Permission expired — reconnect"
                            profile.driveConnected -> profile.email ?: "App folder only"
                            else -> "Encrypted backups in your own Drive"
                        }) { nav.navigate(Routes.DRIVE) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Backup, fin.savings, "Backup & Restore",
                        "Last backup: " + (backupPrefs.lastSuccessAt?.let { com.mymoney.app.core.Dates.formatDay(it) } ?: "never") +
                            if (backupPrefs.autoBackupEnabled) " · Auto ${backupPrefs.frequency.label.lowercase()}" else " · Automatic backup off") { nav.navigate(Routes.BACKUP) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.History, fin.savings, "Backup history", "Restore or delete Drive backups") { nav.navigate(Routes.BACKUP_HISTORY) }
                }
            }
            item { SectionHeader("Widgets") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Widgets, MaterialTheme.colorScheme.secondary, "Widget gallery", "Previews of 7 widgets and how to add them") { nav.navigate(Routes.WIDGETS) }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LabeledSwitchRow("Widget privacy mode", "Mask amounts on home screen widgets", widgetPrefs.privacyMode, vm::setWidgetPrivacy)
                    }
                }
            }
            item { SectionHeader("Notifications") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Tune, fin.accent, "Reminder settings",
                        if (!st.notificationsEnabled) "All notifications off" else "Categories, timing, quiet hours") { nav.navigate(Routes.NOTIFICATION_PREFS) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Notifications, fin.accent, "Notification center", "History of reminders and alerts") { nav.navigate(Routes.NOTIFICATIONS) }
                }
            }
            item { SectionHeader("Automation") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.AutoAwesome, primary, "Auto-capture payments", "Record payments from bank SMS & UPI app notifications") { nav.navigate(Routes.AUTO_CAPTURE) }
                }
            }
            item { SectionHeader("Security") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Password, primary, "App PIN", "Change PIN or create a new recovery code") { nav.navigate(Routes.CHANGE_PIN) }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val available = remember { Biometrics.available(context) }
                        LabeledSwitchRow("Fingerprint unlock", if (available) "Use fingerprint or face to unlock" else "Not available on this device", sec.biometricEnabled && available, { on ->
                            if (on) Biometrics.prompt(context, "Enable biometric unlock", "Confirm it's you", onSuccess = { vm.setBiometric(true) }) else vm.setBiometric(false)
                        }, enabled = available)
                        DropdownField("Auto-lock", PinManager.AUTO_LOCK_OPTIONS.map { it.first }, sec.autoLockSeconds, { v -> PinManager.AUTO_LOCK_OPTIONS.first { it.first == v }.second }, vm::setAutoLock,
                            leading = { Icon(Icons.Filled.Timer, null) })
                        LabeledSwitchRow("Hide balances", "Mask balances on the dashboard until you tap the eye", st.hideBalances, vm::setHideBalances)
                    }
                    Row2(Icons.Filled.Lock, primary, "Lock now", null) { vm.lockNow() }
                }
            }
            item { SectionHeader("Data") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Category, fin.accent, "Custom categories", "Add, edit or archive categories") { nav.navigate(Routes.CATEGORIES) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Description, fin.savings, "Backup to file & export", "Encrypted file backup, CSV and PDF") { nav.navigate(Routes.BACKUP) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Info, fin.transfer, "App information", "Version ${BuildConfig.VERSION_NAME}") { about = true }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.DeleteForever, MaterialTheme.colorScheme.error, "Reset application data", "Erase everything on this device") { erase = true }
                }
            }
        }
    }
    if (editName) {
        var name by remember { mutableStateOf(s?.profileName ?: "") }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Your name") },
            text = { AppTextField(name, { name = it }, "Name", capitalization = KeyboardCapitalization.Words, maxLength = 40) },
            confirmButton = { TextButton(onClick = { vm.setName(name); editName = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text("FinFox ${BuildConfig.VERSION_NAME}") },
        text = {
            Text("Personal finance & expense tracker.\n\n• Works fully offline. The network is used only for your optional Google Drive backups.\n• All financial data is stored on this device.\n• Your PIN is stored as a salted hash protected by the Android Keystore.\n• Backups are encrypted on the device (AES-256-GCM, Argon2id key) before upload.\n• FinFox never asks for bank passwords, card numbers, CVVs or UPI PINs, and never moves real money.")
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
    )
    if (erase) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { erase = false },
            title = { Text("Erase all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently deletes all accounts, transactions, schedules, loans, budgets, goals, settings and your PIN. Make a backup first if you may need this data.")
                    AppTextField(typed, { typed = it }, "Type ERASE to confirm", capitalization = KeyboardCapitalization.Characters, maxLength = 10)
                }
            },
            confirmButton = { TextButton(onClick = { vm.eraseAll(); erase = false }, enabled = typed.trim() == "ERASE") { Text("Erase everything", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { erase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Row2(icon: ImageVector, color: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    ListRow(icon, color, title, subtitle, onClick = onClick) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}
