package com.mymoney.app.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.OccurrenceStatus
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.domain.ScheduleMath
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalDate

@Composable
fun ScheduleDetailScreen(nav: NavController, vm: ScheduleDetailViewModel = hiltViewModel()) {
    val d by vm.schedule.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pay by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val currency = LocalCurrency.current
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    // Never auto-pays: the request only opens the confirmation dialog, after the app lock.
    LaunchedEffect(d) {
        val sch = d?.schedule ?: return@LaunchedEffect
        if (vm.payRequested) {
            vm.consumePayRequest()
            if (sch.status == com.mymoney.app.data.db.ScheduleStatus.ACTIVE) pay = true
        }
    }
    AppScaffold(
        title = "Scheduled Payment", onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { nav.navigate(Routes.scheduleEdit(vm.id)) }) { Icon(Icons.Filled.Edit, "Edit") }
            IconButton(onClick = { del = true }) { Icon(Icons.Filled.Delete, "Delete") }
        },
    ) { pad ->
        val detail = d ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val s = detail.schedule
        val today = Dates.today()
        val due = LocalDate.ofEpochDay(s.nextDueEpochDay)
        val overdue = s.status == ScheduleStatus.ACTIVE && due.isBefore(today)
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(AppIcons.forKey(s.category.iconKey), if (overdue) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.primary, 60.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(s.title, style = MaterialTheme.typography.titleLarge)
                    Text(Money.format(s.amountMinor, currency), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    when (s.status) {
                        ScheduleStatus.ACTIVE -> Chip(Dates.dueLabel(due, today), if (overdue) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.primary)
                        ScheduleStatus.PAUSED -> Chip("Paused", FinFoxTheme.colors.warning)
                        ScheduleStatus.COMPLETED -> Chip("Completed", FinFoxTheme.colors.income)
                    }
                }
            }
            if (s.status == ScheduleStatus.ACTIVE) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Mark ${Dates.formatDayShort(due)} as paid", { pay = true }, loading = busy, icon = Icons.Filled.CheckCircle)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Skip", { skip = true }, Modifier.weight(1f), icon = Icons.Filled.SkipNext)
                        SecondaryButton("Pause", { vm.setPaused(true) }, Modifier.weight(1f), icon = Icons.Filled.Pause)
                    }
                }
            }
            if (s.status == ScheduleStatus.PAUSED) item { PrimaryButton("Resume", { vm.setPaused(false) }, icon = Icons.Filled.PlayArrow) }
            item {
                AppCard {
                    KeyValueRow("Type", s.category.label)
                    KeyValueRow("Repeats", "${s.frequency.label} • ${frequencyText(s)}")
                    KeyValueRow("Pay from", detail.accountName)
                    detail.toAccountName?.let { KeyValueRow("Pays to", "$it (transfer)") }
                    detail.expenseCategoryName?.let { KeyValueRow("Category", it) }
                    KeyValueRow("Next due", Dates.formatDay(due))
                    if (overdue) {
                        val n = ScheduleMath.overdueCount(LocalDate.ofEpochDay(s.startEpochDay), s.frequency, due, s.endEpochDay?.let(LocalDate::ofEpochDay), today)
                        KeyValueRow("Overdue", "$n installment${if (n == 1) "" else "s"}", FinFoxTheme.colors.expense)
                    }
                    KeyValueRow("Started", Dates.formatDay(LocalDate.ofEpochDay(s.startEpochDay)))
                    KeyValueRow("Ends", s.endEpochDay?.let { Dates.formatDay(LocalDate.ofEpochDay(it)) } ?: "No end date")
                    KeyValueRow("Reminder", if (s.reminderDaysBefore == 0) "On the due date" else "${s.reminderDaysBefore} day(s) before")
                    s.notes?.let { KeyValueRow("Notes", it) }
                }
            }
            item { SectionHeader("Payment history") }
            val h = history
            item {
                AppCard {
                    when {
                        h == null -> LoadingState()
                        h.isEmpty() -> Text("No installments recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            val paid = h.filter { it.occurrence.status == OccurrenceStatus.PAID }
                            Text("${paid.size} paid • ${Money.format(paid.sumOf { it.occurrence.amountMinor }, currency)} total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            h.forEach { o ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Due ${Dates.formatDay(LocalDate.ofEpochDay(o.occurrence.dueEpochDay))}", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (o.occurrence.status == OccurrenceStatus.PAID) "Paid ${o.transactionDateMillis?.let { Dates.formatDay(it) } ?: ""}${o.accountName?.let { " from $it" } ?: ""}" else "Skipped",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (o.occurrence.status == OccurrenceStatus.PAID) Text(Money.format(o.occurrence.amountMinor, currency), style = MaterialTheme.typography.titleSmall,
                                        modifier = o.occurrence.transactionId?.let { id -> Modifier.padding(start = 8.dp) } ?: Modifier)
                                    else Chip("Skipped", FinFoxTheme.colors.warning)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val s = d?.schedule
    if (pay && s != null) MarkPaidDialog(s, accounts, { pay = false }) { a, acc, date -> vm.markPaid(a, acc, date) }
    if (skip && s != null) ConfirmDialog("Skip this installment?", "The ${Dates.formatDay(LocalDate.ofEpochDay(s.nextDueEpochDay))} payment will be marked as skipped. No transaction is created.", "Skip", vm::skip, { skip = false }, destructive = false)
    if (del) ConfirmDialog("Delete schedule?", "Future reminders stop. Transactions already recorded for paid installments stay in your history.", "Delete", vm::delete, { del = false })
}
