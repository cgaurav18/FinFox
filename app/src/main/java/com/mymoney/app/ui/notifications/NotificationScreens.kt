package com.mymoney.app.ui.notifications

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.NotificationCategory
import com.mymoney.app.data.db.NotificationHistoryEntity
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TimeField
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalTime

@Composable
private fun categoryIcon(c: NotificationCategory): Pair<ImageVector, Color> = when (c) {
    NotificationCategory.PAYMENT -> Icons.Filled.EventRepeat to FinFoxTheme.colors.warning
    NotificationCategory.LOAN -> Icons.Filled.Groups to FinFoxTheme.colors.transfer
    NotificationCategory.BUDGET -> Icons.Filled.PieChart to FinFoxTheme.colors.accent
    NotificationCategory.GOAL -> Icons.Filled.Savings to FinFoxTheme.colors.savings
    NotificationCategory.BACKUP -> Icons.Filled.Cloud to MaterialTheme.colorScheme.primary
}

@Composable
fun NotificationCenterScreen(nav: NavController, vm: NotificationCenterViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    AppScaffold(
        title = "Notifications", onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = vm::markAllRead, enabled = s.unread > 0) { Icon(Icons.Filled.DoneAll, "Mark all as read") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Notification settings") }, { menu = false; nav.navigate(Routes.NOTIFICATION_PREFS) }, leadingIcon = { Icon(Icons.Filled.Settings, null) })
                    DropdownMenuItem({ Text("Clear all") }, { menu = false; clear = true }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) })
                }
            }
        },
    ) { pad ->
        if (!s.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PillTabs(NotificationFilter.entries, s.filter, { if (it == NotificationFilter.UNREAD && s.unread > 0) "Unread (${s.unread})" else it.label },
                    { vm.filter.value = it }, scrollable = true)
            }
            if (s.items.isEmpty()) item {
                EmptyState(Icons.Filled.NotificationsNone, if (s.filter == NotificationFilter.UNREAD) "You're all caught up" else "No notifications",
                    "Payment reminders, budget alerts, goal milestones and backup results will appear here.")
            }
            items(s.items, key = { it.id }) { n ->
                NotificationRow(n, onOpen = { vm.open(n); n.route?.let { r -> nav.navigate(r) } }, onRead = { vm.markRead(n) }, onDelete = { vm.delete(n) })
            }
        }
    }
    if (clear) ConfirmDialog("Clear all notifications?", "This removes the in-app history. It doesn't change any of your records.", "Clear all",
        onConfirm = vm::clearAll, onDismiss = { clear = false })
}

@Composable
private fun NotificationRow(n: NotificationHistoryEntity, onOpen: () -> Unit, onRead: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (icon, color) = categoryIcon(n.category)
    AppCard(contentPadding = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            IconBadge(icon, color, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (n.read) FontWeight.Medium else FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    if (!n.read) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
                }
                Text(n.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                Spacer(Modifier.height(4.dp))
                Text("${n.category.label} · ${Dates.relativeDayLabel(Dates.toLocalDate(n.createdAt)).substringBefore(',')} ${Dates.formatTime(n.createdAt)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }) {
                    if (n.route != null) DropdownMenuItem({ Text("Open related record") }, { menu = false; onOpen() })
                    if (!n.read) DropdownMenuItem({ Text("Mark as read") }, { menu = false; onRead() })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() })
                }
            }
        }
    }
}

private val reminderDayOptions = listOf(0, 1, 3, 7)

@Composable
fun NotificationPreferencesScreen(nav: NavController, vm: NotificationPreferencesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    val systemAllowed = remember(permissionTick) { vm.canPostToSystem() }
    var customDays by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTick++
        if (granted) vm.updateSettings { it.copy(notificationsEnabled = true) }
    }
    fun requestPermissionThen(on: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !vm.canPostToSystem()) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else on()
    }
    AppScaffold(title = "Notification Settings", onBack = { nav.popBackStack() }) { pad ->
        if (!s.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val st = s.settings; val p = s.prefs
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!systemAllowed) item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text("Notifications are blocked for FinFox. Alerts will still appear in the in-app notification center.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    TonalButton("Allow notifications", {
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }, icon = Icons.Filled.NotificationsActive)
                }
            }
            item {
                AppCard {
                    LabeledSwitchRow("Enable notifications", "Master switch for all reminders and alerts", st.notificationsEnabled, { on ->
                        if (on) requestPermissionThen { vm.updateSettings { it.copy(notificationsEnabled = true) } } else vm.updateSettings { it.copy(notificationsEnabled = false) }
                    })
                }
            }
            item { SectionHeader("Categories") }
            item {
                AppCard {
                    val on = st.notificationsEnabled
                    LabeledSwitchRow("Payment reminders", "EMI, SIP, bills, rent, subscriptions and card dues", p.paymentReminders, { v -> vm.updatePrefs { it.copy(paymentReminders = v) } }, enabled = on)
                    LabeledSwitchRow("Lending & borrowing", "Repayments due, overdue amounts, money expected", p.loanReminders, { v -> vm.updatePrefs { it.copy(loanReminders = v) } }, enabled = on)
                    LabeledSwitchRow("Budget alerts", "At 80%, 90%, 100% and when exceeded", st.budgetAlertsEnabled, { v -> vm.updateSettings { it.copy(budgetAlertsEnabled = v) } }, enabled = on)
                    LabeledSwitchRow("Savings goals", "Milestones, completion and target dates", p.savingsAlerts, { v -> vm.updatePrefs { it.copy(savingsAlerts = v) } }, enabled = on)
                    LabeledSwitchRow("Backup", "Backup results and Drive authorization issues", p.backupAlerts, { v -> vm.updatePrefs { it.copy(backupAlerts = v) } }, enabled = on)
                }
            }
            item { SectionHeader("Reminder timing") }
            item {
                AppCard {
                    DropdownField("Daily reminder time", (6..21).toList(), st.reminderHour, { h -> LocalTime.of(h, 0).format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")) },
                        { h -> vm.updateSettings { it.copy(reminderHour = h) } })
                    Spacer(Modifier.height(8.dp))
                    val options = (reminderDayOptions + p.defaultReminderDays + -1).distinct()
                    DropdownField("Loan & card reminders", options, p.defaultReminderDays, { d ->
                        when (d) { -1 -> "Custom…"; 0 -> "On the due date"; 1 -> "1 day before"; else -> "$d days before" }
                    }, { d -> if (d == -1) customDays = true else vm.updatePrefs { it.copy(defaultReminderDays = d) } })
                    Spacer(Modifier.height(8.dp))
                    DropdownField("Remind later", listOf(1, 3, 6, 12, 24), p.snoozeHours, { "After $it hour${if (it > 1) "s" else ""}" }, { h -> vm.updatePrefs { it.copy(snoozeHours = h) } })
                    Spacer(Modifier.height(6.dp))
                    Text("Each scheduled payment has its own reminder (on the due date, or 1–30 days before) set on the payment. Reminders are checked a few times a day, so they may arrive a little after the chosen time.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader("Quiet hours") }
            item {
                AppCard {
                    LabeledSwitchRow("Quiet hours", "Hold reminders and silence alerts during these hours", p.quietHoursEnabled, { v -> vm.updatePrefs { it.copy(quietHoursEnabled = v) } })
                    if (p.quietHoursEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TimeField("From", LocalTime.of(p.quietStartMinute / 60, p.quietStartMinute % 60), { t -> vm.updatePrefs { it.copy(quietStartMinute = t.hour * 60 + t.minute) } }, Modifier.weight(1f))
                        TimeField("Until", LocalTime.of(p.quietEndMinute / 60, p.quietEndMinute % 60), { t -> vm.updatePrefs { it.copy(quietEndMinute = t.hour * 60 + t.minute) } }, Modifier.weight(1f))
                    }
                }
            }
            item { SectionHeader("Privacy") }
            item {
                AppCard {
                    LabeledSwitchRow("Hide amounts on lock screen", "Show only a generic message until the phone is unlocked", p.hideAmountsOnLockScreen,
                        { v -> vm.updatePrefs { it.copy(hideAmountsOnLockScreen = v) } })
                }
            }
            item { TonalButton("Open notification center", { nav.navigate(Routes.NOTIFICATIONS) }, Modifier.fillMaxWidth(), icon = Icons.Filled.AccountBalanceWallet) }
        }
    }
    if (customDays) {
        CustomDaysDialog(s.prefs.defaultReminderDays, { customDays = false }) { d -> vm.updatePrefs { it.copy(defaultReminderDays = d) } }
    }
}

@Composable
private fun CustomDaysDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom reminder") },
        text = {
            com.mymoney.app.ui.components.AppTextField(text, { if (it.length <= 2 && it.all(Char::isDigit)) text = it }, "Days before the due date (0–30)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, error = if (value == null || value !in 0..30) "Enter 0 to 30" else null)
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = value != null && value in 0..30, onClick = { onSave(value!!); onDismiss() }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
