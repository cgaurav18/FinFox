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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.mymoney.app.ui.theme.FinFoxTheme
import com.mymoney.app.ui.transactions.TxnRow
import java.time.LocalDate

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val hidden by vm.balanceHidden.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val unreadVm: com.mymoney.app.ui.notifications.UnreadCountViewModel = hiltViewModel()
    val unread by unreadVm.unread.collectAsStateWithLifecycle()
    val captureVm: com.mymoney.app.ui.autocapture.CaptureBadgeViewModel = hiltViewModel()
    val capturePending by captureVm.pending.collectAsStateWithLifecycle()
    if (s.loading) { LoadingState(); return }
    val fin = FinFoxTheme.colors
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier.widthIn(max = com.mymoney.app.ui.components.MaxContentWidth).fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(s.name, null, unread, { nav.navigate(Routes.NOTIFICATIONS) }) { nav.navigate(Routes.SETTINGS) } }
        if (profile.driveConnected && profile.driveNeedsReauth) item {
            Notice(Icons.Filled.CloudOff, MaterialTheme.colorScheme.error, "Reconnect Google Drive", "Automatic backups are paused until you allow access again.") { nav.navigate(Routes.BACKUP) }
        }
        if (capturePending > 0) item {
            Notice(Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary,
                "$capturePending captured ${if (capturePending == 1) "payment needs" else "payments need"} review",
                "Confirm the account and category to add ${if (capturePending == 1) "it" else "them"}.") { nav.navigate(Routes.AUTO_CAPTURE) }
        }
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
        item { MonthSummary(s, nav) }
        if (s.monthData.slices.isNotEmpty()) item {
            AppCard {
                Text("Where your money went", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                DonutChart(s.monthData.slices, "Expenses", s.monthData.expense)
            }
        }
        item { SectionHeader("Recent transactions", action = "See all") { nav.navigate(Routes.txns()) } }
        item {
            AppCard(contentPadding = 0.dp) {
                if (s.recent.isEmpty()) EmptyState(Icons.Filled.ReceiptLong, "No transactions yet", "Tap + to record your first income or expense.")
                else s.recent.forEach { TxnRow(it, { nav.navigate(Routes.txnDetail(it.txn.id)) }) }
            }
        }
        if (s.upcoming.isNotEmpty()) {
            item { SectionHeader("Upcoming payments", action = "See all") { nav.navigate(Routes.SCHEDULES) } }
            item { AppCard(contentPadding = 0.dp) { s.upcoming.forEach { UpcomingRow(it, s.today) { nav.navigate(Routes.scheduleDetail(it.schedule.id)) } } } }
        }
        if (s.loans.lent > 0 || s.loans.borrowed > 0) {
            item { SectionHeader("Money with people", action = "See all") { nav.navigate(Routes.PEOPLE) } }
            item {
                AppCard(onClick = { nav.navigate(Routes.PEOPLE) }) {
                    Row {
                        LoanPart("To receive", s.loans.lent, s.loans.lentOverdue, fin.income, Modifier.weight(1f))
                        LoanPart("To pay", s.loans.borrowed, s.loans.borrowedOverdue, fin.expense, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    }
}

/** A one-line alert card (backup paused, payments to review). */
@Composable
private fun Notice(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(onClick = onClick, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Income, expenses and what's left this month, with how much of the income has been spent. */
@Composable
private fun MonthSummary(s: HomeState, nav: NavController) {
    val fin = FinFoxTheme.colors
    val d = s.monthData
    AppCard {
        Row {
            SummaryPart("Income", d.income, fin.income, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.txns(TxnType.INCOME.name)) })
            SummaryPart("Expenses", d.expense, fin.expense, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.txns(TxnType.EXPENSE.name)) })
            SummaryPart("Left", d.net, if (d.net >= 0) MaterialTheme.colorScheme.onSurface else fin.expense, Modifier.weight(1f))
        }
        if (d.income > 0) {
            Spacer(Modifier.height(14.dp))
            val spent = (d.expense.toFloat() / d.income).coerceIn(0f, 1f)
            com.mymoney.app.ui.components.ProgressBar(spent, if (d.expense > d.income) fin.expense else MaterialTheme.colorScheme.primary, height = 6.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.expense > d.income) "You spent more than you earned this month"
                else "${Money.percent(d.expense, d.income)}% of income spent · ${Money.percent(d.net.coerceAtLeast(0), d.income)}% saved",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryPart(label: String, amount: Long, color: Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun LoanPart(label: String, amount: Long, overdue: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, color = color)
        if (overdue > 0) Text("${Money.format(overdue, LocalCurrency.current)} overdue", style = MaterialTheme.typography.labelSmall, color = FinFoxTheme.colors.expense)
    }
}

@Composable
private fun Header(name: String, photoUrl: String?, unread: Int, onNotifications: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name.substringBefore(' ').ifBlank { "Welcome" }, style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onNotifications) {
            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                Icon(Icons.Filled.Notifications, if (unread > 0) "Notifications, $unread unread" else "Notifications", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.clip(CircleShape).clickable(onClick = onProfile)) { com.mymoney.app.ui.account.Avatar(photoUrl, name, 40.dp) }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

@Composable
private fun BalanceCard(s: HomeState, hidden: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val onG = FinFoxTheme.colors.onGradient
    fun fmt(v: Long) = if (hidden) Money.symbolFor(currency) + " •••••" else Money.format(v, currency)
    val shown = animatedMinor(s.totals.availableMinor)
    Column(
        Modifier.fillMaxWidth().clip(com.mymoney.app.ui.theme.FinFoxShapes.heroCard).background(FinFoxTheme.colors.headerGradient).clickable(onClick = onClick).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total balance", color = onG.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (hidden) "Show balance" else "Hide balance", tint = onG)
            }
        }
        Text(fmt(shown), color = onG, style = MaterialTheme.typography.displayMedium.copy(fontSize = 32.sp), maxLines = 1,
            modifier = Modifier.semantics { contentDescription = if (hidden) "Balance hidden" else "Total available balance ${Money.format(s.totals.availableMinor, currency)}" })
        Text("Bank + cash. Credit limits aren't counted.", color = onG.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).padding(12.dp)) {
            BalancePart("Bank", fmt(s.totals.bankMinor), Icons.Filled.AccountBalance, Modifier.weight(1f))
            BalancePart("Cash", fmt(s.totals.cashMinor), Icons.Filled.AccountBalanceWallet, Modifier.weight(1f))
            BalancePart("Card due", fmt(s.totals.creditOutstandingMinor), Icons.Filled.CreditCard, Modifier.weight(1f))
        }
    }
}

/** Animates a money amount from its previous value (balance updates), honouring reduced motion. */
@Composable
private fun animatedMinor(target: Long): Long {
    val reduced = FinFoxTheme.reducedMotion
    val anim = remember { androidx.compose.animation.core.Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        if (reduced) anim.snapTo(target.toFloat()) else anim.animateTo(target.toFloat(), androidx.compose.animation.core.tween(700))
    }
    // Float only drives the transition; the final frame always shows the exact Long amount.
    return if (anim.isRunning) kotlin.math.round(anim.value / 100f).toLong() * 100 else target
}

@Composable
private fun BalancePart(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    val onG = FinFoxTheme.colors.onGradient
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = onG.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = onG.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        Text(value, color = onG, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class QA(val label: String, val icon: ImageVector, val color: Color, val route: String)

/** The four things people do most: record an expense, record income, move money, pay by UPI. */
@Composable
private fun QuickActions(nav: NavController) {
    val fin = FinFoxTheme.colors
    val actions = listOf(
        QA("Expense", Icons.Filled.ArrowUpward, fin.expense, Routes.txnEdit(type = TxnType.EXPENSE.name)),
        QA("Income", Icons.Filled.ArrowDownward, fin.income, Routes.txnEdit(type = TxnType.INCOME.name)),
        QA("Transfer", Icons.Filled.SwapHoriz, fin.transfer, Routes.txnEdit(type = TxnType.TRANSFER.name)),
        QA("Scan & Pay", Icons.Filled.QrCodeScanner, MaterialTheme.colorScheme.primary, Routes.pay(scan = true)),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.forEach { a ->
            AppCard(Modifier.weight(1f), onClick = { nav.navigate(a.route) }, contentPadding = 10.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(a.icon, a.color, 38.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun UpcomingRow(d: ScheduleDetail, today: LocalDate, onClick: () -> Unit) {
    val due = LocalDate.ofEpochDay(d.schedule.nextDueEpochDay)
    val overdue = due.isBefore(today)
    ListRow(AppIcons.forKey(d.schedule.category.iconKey), if (overdue) FinFoxTheme.colors.expense else MaterialTheme.colorScheme.primary, d.schedule.title,
        "${Dates.dueLabel(due, today)} • ${Dates.formatDay(due)}", onClick = onClick) {
        MoneyText(d.schedule.amountMinor, style = MaterialTheme.typography.titleSmall, color = if (overdue) FinFoxTheme.colors.expense else Color.Unspecified)
    }
}
