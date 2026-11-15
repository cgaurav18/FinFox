package com.mymoney.app.ui.home

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.ScheduleDetail
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.DonutChart
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.IncomeExpenseBarChart
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.MonthSelector
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.StatTile
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme
import com.mymoney.app.ui.transactions.TxnRow
import java.time.LocalDate

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val hidden by vm.balanceHidden.collectAsStateWithLifecycle()
    if (s.loading) { LoadingState(); return }
    val fin = FinanceTheme.colors
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(s.name) { nav.navigate(Routes.SETTINGS) } }
        item { BalanceCard(s, hidden, vm::toggleHidden) { nav.navigate(Routes.ACCOUNTS) } }
        item { QuickActions(nav) }
        if (s.accountCount == 0) item {
            AppCard { EmptyState(Icons.Filled.AccountBalance, "Add your first account", "Add a bank account, credit card or cash wallet to start tracking.", action = "Add account") { nav.navigate(Routes.accountEdit()) } }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                MonthSelector(s.month, { vm.month.value = it }, allowFuture = false)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Income", s.monthData.income, fin.income, Icons.Filled.ArrowDownward, Modifier.weight(1f)) { nav.navigate(Routes.txns(TxnType.INCOME.name)) }
                StatTile("Expenses", s.monthData.expense, fin.expense, Icons.Filled.ArrowUpward, Modifier.weight(1f)) { nav.navigate(Routes.txns(TxnType.EXPENSE.name)) }
            }
        }
        item {
            AppCard(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Savings, MaterialTheme.colorScheme.primary, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Net savings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(s.monthData.net, color = if (s.monthData.net >= 0) fin.income else fin.expense, signed = true)
                    }
                    if (s.monthData.income > 0) Text("${Money.percent(s.monthData.net.coerceAtLeast(0), s.monthData.income)}% saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoanTile("To receive", s.loans.lent, s.loans.lentOverdue, fin.income, Icons.AutoMirrored.Filled.CallReceived, Modifier.weight(1f)) { nav.navigate(Routes.PEOPLE) }
                LoanTile("To pay", s.loans.borrowed, s.loans.borrowedOverdue, fin.expense, Icons.AutoMirrored.Filled.CallMade, Modifier.weight(1f)) { nav.navigate(Routes.PEOPLE) }
            }
        }
        item { SectionHeader("Upcoming payments", action = "View all") { nav.navigate(Routes.SCHEDULES) } }
        item {
            AppCard(contentPadding = 0.dp) {
                if (s.upcoming.isEmpty()) {
                    Text("No scheduled payments. Add EMIs, SIPs and bills to get reminders.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                } else s.upcoming.forEach { UpcomingRow(it, s.today) { nav.navigate(Routes.scheduleDetail(it.schedule.id)) } }
            }
        }
        item {
            AppCard {
                Text("Income vs expenses", style = MaterialTheme.typography.titleSmall)
                Text("Last 6 months", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                IncomeExpenseBarChart(s.monthData.sixMonths)
            }
        }
        if (s.monthData.slices.isNotEmpty()) item {
            AppCard {
                Text("Spending by category", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                DonutChart(s.monthData.slices, "Expenses", s.monthData.expense)
            }
        }
        item { SectionHeader("Recent transactions", action = "View all") { nav.navigate(Routes.txns()) } }
        item {
            AppCard(contentPadding = 0.dp) {
                if (s.recent.isEmpty()) EmptyState(Icons.Filled.ReceiptLong, "No transactions yet", "Tap + to record your first income or expense.")
                else s.recent.forEach { TxnRow(it, { nav.navigate(Routes.txnDetail(it.txn.id)) }) }
            }
        }
    }
}

@Composable
private fun Header(name: String, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("My Money", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(greeting() + if (name.isNotBlank()) ", ${name.substringBefore(' ')}" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) { Text(name.trim().firstOrNull()?.uppercase() ?: "M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

@Composable
private fun BalanceCard(s: HomeState, hidden: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    fun fmt(v: Long) = if (hidden) Money.symbolFor(currency) + " •••••" else Money.format(v, currency)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(FinanceTheme.colors.headerGradient).clickable(onClick = onClick).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total available balance", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (hidden) "Show balance" else "Hide balance", tint = Color.White)
            }
        }
        Text(fmt(s.totals.availableMinor), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("Bank + cash. Credit limits aren't counted.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).padding(12.dp)) {
            BalancePart("Bank", fmt(s.totals.bankMinor), Icons.Filled.AccountBalance, Modifier.weight(1f))
            BalancePart("Cash", fmt(s.totals.cashMinor), Icons.Filled.AccountBalanceWallet, Modifier.weight(1f))
            BalancePart("Card due", fmt(s.totals.creditOutstandingMinor), Icons.Filled.CreditCard, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BalancePart(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class QA(val label: String, val icon: ImageVector, val color: Long, val route: String)

@Composable
private fun QuickActions(nav: NavController) {
    val actions = listOf(
        QA("Add Expense", Icons.Filled.ArrowUpward, 0xFFDC2626, Routes.txnEdit(type = TxnType.EXPENSE.name)),
        QA("Add Income", Icons.Filled.ArrowDownward, 0xFF16A34A, Routes.txnEdit(type = TxnType.INCOME.name)),
        QA("Transfer", Icons.Filled.SwapHoriz, 0xFF2563EB, Routes.txnEdit(type = TxnType.TRANSFER.name)),
        QA("Accounts", Icons.Filled.AddCard, 0xFF7C3AED, Routes.ACCOUNTS),
        QA("Scheduled", Icons.Filled.EventRepeat, 0xFFF97316, Routes.SCHEDULES),
        QA("Give / Receive", Icons.Filled.Groups, 0xFF0891B2, Routes.PEOPLE),
        QA("Budgets", Icons.Filled.PieChart, 0xFFDB2777, Routes.BUDGETS),
        QA("Reports", Icons.Filled.BarChart, 0xFF5B21B6, Routes.REPORTS),
    )
    AppCard(contentPadding = 8.dp) {
        actions.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                            if (a.route == Routes.REPORTS) nav.navigate(Routes.REPORTS) { launchSingleTop = true } else nav.navigate(a.route)
                        }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconBadge(a.icon, Color(a.color), 44.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(a.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoanTile(label: String, amount: Long, overdue: Long, color: Color, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyText(amount, style = MaterialTheme.typography.titleSmall)
                if (overdue > 0) Text("${Money.format(overdue, LocalCurrency.current)} overdue", style = MaterialTheme.typography.labelSmall, color = FinanceTheme.colors.expense)
            }
        }
    }
}

@Composable
fun UpcomingRow(d: ScheduleDetail, today: LocalDate, onClick: () -> Unit) {
    val due = LocalDate.ofEpochDay(d.schedule.nextDueEpochDay)
    val overdue = due.isBefore(today)
    ListRow(AppIcons.forKey(d.schedule.category.iconKey), if (overdue) FinanceTheme.colors.expense else MaterialTheme.colorScheme.primary, d.schedule.title,
        "${Dates.dueLabel(due, today)} • ${Dates.formatDay(due)}", onClick = onClick) {
        MoneyText(d.schedule.amountMinor, style = MaterialTheme.typography.titleSmall, color = if (overdue) FinanceTheme.colors.expense else Color.Unspecified)
    }
}
