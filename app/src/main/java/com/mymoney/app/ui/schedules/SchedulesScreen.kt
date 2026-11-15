package com.mymoney.app.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.ScheduleDetail
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.domain.ScheduleMath
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.MonthSelector
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.StatTile
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun SchedulesScreen(nav: NavController, vm: SchedulesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val month by vm.calendarMonth.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf<ScheduleDetail?>(null) }
    AppScaffold(
        title = "Scheduled Payments", onBack = { nav.popBackStack() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.scheduleEdit()) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add Schedule") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Due this month", s.dueThisMonthMinor, MaterialTheme.colorScheme.primary, Icons.Filled.EventRepeat, Modifier.weight(1f))
                    AppCard(Modifier.weight(1f), onClick = { vm.view.value = ScheduleView.OVERDUE }, contentPadding = 12.dp) {
                        Text("Overdue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${s.overdueCount} payment${if (s.overdueCount == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall, color = if (s.overdueCount > 0) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item { PillTabs(ScheduleView.entries, view, { it.label }, { vm.view.value = it }) }
            item { PillTabs(ScheduleGroup.entries, group, { it.label }, { vm.group.value = it }, scrollable = true) }
            when {
                s.loading -> item { LoadingState() }
                view == ScheduleView.CALENDAR -> item { CalendarView(s.all, month, { vm.calendarMonth.value = it }, s.today) { nav.navigate(Routes.scheduleDetail(it)) } }
                s.items.isEmpty() -> item {
                    EmptyState(Icons.Filled.EventRepeat, when (view) { ScheduleView.OVERDUE -> "Nothing overdue"; ScheduleView.UPCOMING -> "No upcoming payments"; else -> "No schedules yet" },
                        "Add EMIs, SIPs, bills and subscriptions. You'll get reminders and can mark them paid in one tap. Money is never deducted automatically.",
                        action = "Add schedule") { nav.navigate(Routes.scheduleEdit()) }
                }
                else -> item {
                    AppCard(contentPadding = 0.dp) {
                        s.items.forEachIndexed { i, d ->
                            ScheduleRow(d, s.today, onClick = { nav.navigate(Routes.scheduleDetail(d.schedule.id)) }, onPay = { paying = d })
                            if (i < s.items.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    paying?.let { d -> MarkPaidDialog(d.schedule, accounts, { paying = null }) { a, acc, date -> vm.markPaid(d.schedule.id, a, acc, date) } }
}

@Composable
private fun ScheduleRow(d: ScheduleDetail, today: LocalDate, onClick: () -> Unit, onPay: () -> Unit) {
    val s = d.schedule
    val due = LocalDate.ofEpochDay(s.nextDueEpochDay)
    val overdue = s.status == ScheduleStatus.ACTIVE && due.isBefore(today)
    val sub = when (s.status) {
        ScheduleStatus.PAUSED -> "Paused • ${frequencyText(s)}"
        ScheduleStatus.COMPLETED -> "Completed"
        ScheduleStatus.ACTIVE -> {
            val n = ScheduleMath.overdueCount(LocalDate.ofEpochDay(s.startEpochDay), s.frequency, due, s.endEpochDay?.let(LocalDate::ofEpochDay), today)
            Dates.dueLabel(due, today) + (if (n > 1) " • $n missed" else "") + " • ${frequencyText(s)}"
        }
    }
    ListRow(AppIcons.forKey(s.category.iconKey), if (overdue) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.primary, s.title, sub, onClick = onClick) {
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(s.amountMinor, style = MaterialTheme.typography.titleSmall, color = if (overdue) FinFoxTheme.colors.expense else Color.Unspecified)
            if (s.status == ScheduleStatus.ACTIVE) FilledTonalButton(onClick = onPay, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                Text("Pay", style = MaterialTheme.typography.labelMedium)
            } else Chip(s.status.name.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarView(all: List<ScheduleDetail>, month: YearMonth, onMonth: (YearMonth) -> Unit, today: LocalDate, onOpen: (Long) -> Unit) {
    val dues: Map<LocalDate, List<ScheduleDetail>> = remember(all, month) {
        val out = mutableMapOf<LocalDate, MutableList<ScheduleDetail>>()
        all.filter { it.schedule.status == ScheduleStatus.ACTIVE }.forEach { d ->
            val s = d.schedule
            val from = month.atDay(1); val to = month.atEndOfMonth()
            val next = LocalDate.ofEpochDay(s.nextDueEpochDay)
            // Overdue installments are shown on their original due date when that falls in this month.
            val dates = ScheduleMath.occurrencesBetween(LocalDate.ofEpochDay(s.startEpochDay), s.frequency, next, s.endEpochDay?.let(LocalDate::ofEpochDay), from, to)
            dates.forEach { out.getOrPut(it) { mutableListOf() }.add(d) }
        }
        out
    }
    var selected by remember(month) { mutableStateOf<LocalDate?>(if (YearMonth.from(today) == month) today else null) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Payment calendar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MonthSelector(month, onMonth)
        }
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        Spacer(Modifier.height(4.dp))
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = (0 until lead).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val has = dues[day].orEmpty()
                            val overdue = has.isNotEmpty() && day.isBefore(today)
                            val isSel = day == selected
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .then(if (day == today && !isSel) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable { selected = day },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = if (has.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                if (has.isNotEmpty()) Box(Modifier.size(6.dp).clip(CircleShape).background(if (isSel) MaterialTheme.colorScheme.onPrimary else if (overdue) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }
        val sel = selected
        if (sel != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(Dates.formatDay(sel), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp))
            val list = dues[sel].orEmpty()
            if (list.isEmpty()) Text("No payments due", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(d.schedule.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.forKey(d.schedule.category.iconKey), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(d.schedule.title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    MoneyText(d.schedule.amountMinor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
