package com.mymoney.app.ui.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.core.PeriodFilter
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DonutChart
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IncomeExpenseBarChart
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LineChart
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(nav: NavController, vm: ReportsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val f by vm.filter.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var exportMenu by remember { mutableStateOf(false) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let(vm::exportPdf) }
    val fin = FinanceTheme.colors
    val currency = LocalCurrency.current
    AppScaffold(
        title = "Reports",
        actions = {
            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.FileDownload, "Export") }
            DropdownMenu(exportMenu, { exportMenu = false }) {
                DropdownMenuItem({ Text("Export CSV") }, { exportMenu = false; vm.beforeExternalActivity(); csv.launch(vm.fileStem() + ".csv") }, leadingIcon = { Icon(Icons.Filled.TableChart, null) })
                DropdownMenuItem({ Text("Export PDF") }, { exportMenu = false; vm.beforeExternalActivity(); pdf.launch(vm.fileStem() + ".pdf") }, leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) })
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodFilter.entries.forEach { p -> FilterChip(f.period == p, { vm.filter.value = f.copy(period = p, custom = if (p == PeriodFilter.CUSTOM) f.custom ?: f.range else null) }, { Text(p.label) }) }
                }
            }
            if (f.period == PeriodFilter.CUSTOM) item {
                val r = f.custom ?: f.range
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("From", r.start, { d -> vm.filter.value = f.copy(custom = DateRange(d, maxOf(d, r.endInclusive))) }, Modifier.weight(1f))
                    DateField("To", r.endInclusive, { d -> vm.filter.value = f.copy(custom = DateRange(minOf(d, r.start), d)) }, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("Account", listOf<Long?>(null) + accounts.map { it.account.id }, f.accountId, { id -> accounts.firstOrNull { it.account.id == id }?.account?.name ?: "All" }, { vm.filter.value = f.copy(accountId = it) }, Modifier.weight(1f))
                    DropdownField("Category", listOf<Long?>(null) + categories.map { it.id }, f.categoryId, { id -> categories.firstOrNull { it.id == id }?.name ?: "All" }, { vm.filter.value = f.copy(categoryId = it) }, Modifier.weight(1f))
                }
            }
            item { Text("${Dates.formatDay(f.range.start)} – ${Dates.formatDay(f.range.endInclusive)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            if (s.loading) { item { LoadingState() }; return@LazyColumn }
            // Income vs expenses
            item {
                AppCard {
                    Text("Income vs expenses", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Column(Modifier.weight(1f)) { Text("Income", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(s.summary.incomeMinor, color = fin.income) }
                        Column(Modifier.weight(1f)) { Text("Expenses", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(s.summary.expenseMinor, color = fin.expense) }
                        Column(Modifier.weight(1f)) { Text("Net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); MoneyText(s.summary.netMinor, color = if (s.summary.netMinor >= 0) fin.income else fin.expense, signed = true) }
                    }
                    if (s.summary.transferMinor > 0) Text("Transfers of ${Money.format(s.summary.transferMinor, currency)} are excluded from income and expenses.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                    IncomeExpenseBarChart(s.series)
                }
            }
            // Cash flow
            item {
                AppCard {
                    Text("Cash flow", style = MaterialTheme.typography.titleSmall)
                    Text("Running net (income − expenses) over the period", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    LineChart(s.cumulative, s.cumulativeLabels, "Net to date", color = if ((s.cumulative.lastOrNull() ?: 0) >= 0) fin.income else fin.expense)
                }
            }
            item {
                AppCard {
                    Text("Category-wise spending", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    if (s.categories.isEmpty()) EmptyState(Icons.Filled.BarChart, "No expenses", "No spending in this period.")
                    else DonutChart(s.categories, "Expenses", s.summary.expenseMinor)
                }
            }
            if (s.incomeCategories.isNotEmpty()) item {
                AppCard {
                    Text("Income sources", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    DonutChart(s.incomeCategories, "Income", s.summary.incomeMinor)
                }
            }
            item {
                AppCard {
                    Text("Account-wise spending", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (s.accountSpending.isEmpty()) Text("No expenses in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.accountSpending.forEach { sl ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row { Text(sl.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(Money.format(sl.valueMinor, currency), style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(4.dp))
                            ProgressBar(sl.percent / 100f, Color(sl.colorArgb), height = 6.dp)
                        }
                    }
                }
            }
            item {
                AppCard(onClick = { nav.navigate(Routes.ACCOUNTS) }) {
                    Text("Credit card outstanding", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    if (s.cards.isEmpty()) Text("No credit cards added.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.cards.forEach { c ->
                        val limit = c.account.creditLimitMinor ?: 0
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row { Text(c.account.name, Modifier.weight(1f)); MoneyText(c.outstandingMinor, style = MaterialTheme.typography.bodyMedium, color = fin.expense) }
                            if (limit > 0) { Spacer(Modifier.height(4.dp)); ProgressBar(c.outstandingMinor.toFloat() / limit, fin.expense, height = 6.dp); Text("${Money.percent(c.outstandingMinor.coerceAtLeast(0), limit)}% of ${Money.format(limit, currency)} limit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                AppCard(onClick = { nav.navigate(Routes.BUDGETS) }) {
                    Text("Budget utilisation • ${Dates.formatMonth(s.budgetMonth)}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    if (s.budgets.isEmpty()) Text("No budgets for this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.budgets.forEach { b ->
                        val p = Money.percent(b.spentMinor, b.budget.limitMinor)
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.forKey(b.categoryIconKey), null, tint = Color(b.categoryColor), modifier = Modifier.padding(end = 8.dp))
                                Text(b.categoryName, Modifier.weight(1f)); Text("$p%", color = if (p >= 100) fin.expense else if (p >= 80) fin.warning else fin.income)
                            }
                            ProgressBar(b.spentMinor.toFloat() / b.budget.limitMinor, if (p >= 100) fin.expense else if (p >= 80) fin.warning else fin.income, height = 6.dp)
                        }
                    }
                }
            }
            item {
                AppCard(onClick = { nav.navigate(Routes.SCHEDULES) }) {
                    Text("Scheduled payments", style = MaterialTheme.typography.titleSmall)
                    KeyValueRow("Active / paused", "${s.schedules.active} / ${s.schedules.paused}")
                    KeyValueRow("Overdue now", "${s.schedules.overdue}", if (s.schedules.overdue > 0) fin.expense else Color.Unspecified)
                    KeyValueRow("Still due in period", Money.format(s.schedules.dueInPeriodMinor, currency))
                    KeyValueRow("Paid in period", "${Money.format(s.schedules.paidInPeriodMinor, currency)} (${s.schedules.paidCount})")
                }
            }
            item {
                AppCard(onClick = { nav.navigate(Routes.PEOPLE) }) {
                    Text("Money lent & borrowed", style = MaterialTheme.typography.titleSmall)
                    KeyValueRow("To receive (total)", Money.format(s.lent, currency), fin.income)
                    KeyValueRow("To pay (total)", Money.format(s.borrowed, currency), fin.expense)
                    KeyValueRow("Lent in period", Money.format(s.lentInPeriod, currency))
                    KeyValueRow("Borrowed in period", Money.format(s.borrowedInPeriod, currency))
                }
            }
            item {
                AppCard(onClick = { nav.navigate(Routes.GOALS) }) {
                    Text("Savings progress", style = MaterialTheme.typography.titleSmall)
                    if (s.goals.isEmpty()) Text("No active goals.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    s.goals.forEach { g ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row { Text(g.goal.name, Modifier.weight(1f)); Text("${Money.format(g.savedMinor, currency)} / ${Money.format(g.goal.targetMinor, currency)}", style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.height(4.dp))
                            ProgressBar(g.savedMinor.toFloat() / g.goal.targetMinor, Color(g.goal.colorArgb), height = 6.dp)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    com.mymoney.app.ui.components.TonalButton("Export CSV", { vm.beforeExternalActivity(); csv.launch(vm.fileStem() + ".csv") }, Modifier.weight(1f), icon = Icons.Filled.Description)
                    com.mymoney.app.ui.components.TonalButton("Export PDF", { vm.beforeExternalActivity(); pdf.launch(vm.fileStem() + ".pdf") }, Modifier.weight(1f), icon = Icons.Filled.PictureAsPdf)
                }
            }
        }
    }
}
