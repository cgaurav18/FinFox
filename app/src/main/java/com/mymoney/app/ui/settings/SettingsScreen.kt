package com.mymoney.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CurrencyRupee
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
    val context = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.setNotifications(granted) }
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
                AppCard {
                    Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PillTabs(ThemeMode.entries, st.themeMode, { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } }, vm::setTheme, Modifier.padding(top = 8.dp))
                }
            }
            item { SectionHeader("Security") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Password, primary, "Change PIN", "Also lets you create a new recovery code") { nav.navigate(Routes.CHANGE_PIN) }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val available = remember { Biometrics.available(context) }
                        LabeledSwitchRow("Biometric unlock", if (available) "Use fingerprint or face to unlock" else "Not available on this device", sec.biometricEnabled && available, { on ->
                            if (on) Biometrics.prompt(context, "Enable biometric unlock", "Confirm it's you", onSuccess = { vm.setBiometric(true) }) else vm.setBiometric(false)
                        }, enabled = available)
                        DropdownField("Auto-lock", PinManager.AUTO_LOCK_OPTIONS.map { it.first }, sec.autoLockSeconds, { v -> PinManager.AUTO_LOCK_OPTIONS.first { it.first == v }.second }, vm::setAutoLock,
                            leading = { Icon(Icons.Filled.Timer, null) })
                    }
                    Row2(Icons.Filled.Lock, primary, "Lock now", null) { vm.lockNow() }
                }
            }
            item { SectionHeader("Notifications") }
            item {
                AppCard {
                    LabeledSwitchRow("Payment & loan reminders", "Upcoming and overdue scheduled payments, loan due dates", st.notificationsEnabled, { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotifications(on)
                    })
                    LabeledSwitchRow("Budget alerts", "Notify at 80%, 90% and 100% of a budget", st.budgetAlertsEnabled, { on -> vm.update { it.copy(budgetAlertsEnabled = on) } }, enabled = st.notificationsEnabled)
                    DropdownField("Reminder time", (6..21).toList(), st.reminderHour, { h -> String.format("%02d:00", h) + if (h < 12) " AM" else " PM" }, { h -> vm.update { it.copy(reminderHour = h) } }, Modifier.padding(top = 8.dp))
                }
            }
            item { SectionHeader("Data") }
            item {
                AppCard(contentPadding = 0.dp) {
                    Row2(Icons.Filled.Category, Color(0xFFF97316), "Custom categories", "Add, edit or archive categories") { nav.navigate(Routes.CATEGORIES) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Backup, Color(0xFF16A34A), "Backup, restore & export", "Encrypted backup, CSV and PDF") { nav.navigate(Routes.BACKUP) }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row2(Icons.Filled.Info, Color(0xFF0891B2), "App information", "Version ${BuildConfig.VERSION_NAME}") { about = true }
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
        title = { Text("My Money ${BuildConfig.VERSION_NAME}") },
        text = {
            Text("Personal finance & expense tracker.\n\n• Works fully offline — the app has no internet permission.\n• All data is stored only on this device.\n• Your PIN is stored as a salted hash protected by the Android Keystore.\n• Backups are encrypted with your password (AES-256-GCM).\n• My Money never asks for bank passwords, card numbers, CVVs or UPI PINs, and never moves real money.")
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
