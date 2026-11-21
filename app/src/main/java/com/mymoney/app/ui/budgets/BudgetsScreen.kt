package com.mymoney.app.ui.budgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.BudgetWithSpent
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.BudgetRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.domain.BudgetInsights
import com.mymoney.app.domain.BudgetLine
import com.mymoney.app.domain.BudgetStatus
import com.mymoney.app.domain.BudgetSummary
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.MessageBus
import com.mymoney.app.ui.components.MonthSelector
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.theme.FinFoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

/** Everything the Budgets screen shows for one month. */
data class BudgetsUi(
    val entities: List<BudgetWithSpent>,
    val summary: BudgetSummary,
    /** Top expense categories without a budget this month: (category, spent). */
    val unbudgeted: List<Pair<CategoryEntity, Long>>,
    /** Last month had budgets that this month doesn't: offer to copy them. */
    val canCopyPrevious: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val repo: BudgetRepository,
    categories: CategoryRepository,
    txns: TransactionRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val month = MutableStateFlow(YearMonth.now())
    val months = repo.observeMonths().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.observe(CategoryType.EXPENSE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Expenses per category for the month and the one before it. */
    private val spending = month.flatMapLatest { ym ->
        txns.observeInRange(DateRange(ym.minusMonths(1).atDay(1), ym.atEndOfMonth())).map { list ->
            val exp = list.filter { it.txn.type == TxnType.EXPENSE && it.txn.categoryId != null }
            val cur = DateRange.month(ym)
            val (now, before) = exp.partition { cur.contains(it.txn.dateMillis) }
            Triple(ym, now.groupBy { it.txn.categoryId!! }.mapValues { e -> e.value.sumOf { it.txn.amountMinor } },
                before.groupBy { it.txn.categoryId!! }.mapValues { e -> e.value.sumOf { it.txn.amountMinor } })
        }
    }

    val ui = combine(month.flatMapLatest { repo.observeMonth(it) }, spending, this.categories, months) { budgets, sp, cats, ms ->
        val (ym, cur, prev) = sp
        val summary = BudgetInsights.summarize(ym, budgets.associate { it.budget.categoryId to it.budget.limitMinor }, cur, prev, Dates.today())
        val budgeted = budgets.map { it.budget.categoryId }.toSet()
        val unbudgeted = cur.filterKeys { it !in budgeted }.entries.sortedByDescending { it.value }
            .mapNotNull { (id, v) -> cats.firstOrNull { it.id == id }?.let { it to v } }.take(4)
        BudgetsUi(budgets, summary, unbudgeted, budgets.isEmpty() && Dates.ymKey(ym.minusMonths(1)) in ms)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(id: Long?, categoryId: Long?, limit: Long) = launchSafe(if (id == null) "Budget added" else "Budget updated") { repo.save(id, categoryId, month.value, limit) }
    fun delete(id: Long) = launchSafe("Budget deleted") { repo.delete(id) }
    fun copyPrevious() = launchSafe { val n = repo.copyFromPrevious(month.value); bus.show(if (n == 0) "All budgets already exist this month" else "Copied $n budget(s)") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetsScreen(nav: NavController, vm: BudgetsViewModel = hiltViewModel()) {
    val month by vm.month.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BudgetWithSpent?>(null) }
    var adding by remember { mutableStateOf<Long?>(null) } // category to preselect; -1 = none
    var deleting by remember { mutableStateOf<BudgetWithSpent?>(null) }
    AppScaffold(
        title = "Budgets", onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = vm::copyPrevious) { Icon(Icons.Filled.ContentCopy, "Copy last month's budgets") } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = -1 }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add budget") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { MonthSelector(month, { vm.month.value = it }) } }
            val u = ui
            when {
                u == null -> item { LoadingState() }
                u.entities.isEmpty() -> {
                    if (u.canCopyPrevious) item {
                        AppCard {
                            Text("Start ${Dates.formatMonth(month)} with last month's budgets", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text("Copies every category limit from ${Dates.formatMonth(month.minusMonths(1))}. You can change any of them after.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton("Copy last month's budgets", onClick = vm::copyPrevious, icon = Icons.Filled.ContentCopy)
                        }
                    }
                    item {
                        EmptyState(Icons.Filled.PieChart, "No budgets for ${Dates.formatMonth(month)}",
                            "Set a monthly limit per category. FinFox shows how much is left per day, warns when you're spending too fast, and sends a summary each month.",
                            action = "Add budget") { adding = -1 }
                    }
                }
                else -> {
                    item { Overview(u.summary) }
                    item { SectionHeader("Categories") }
                    items(u.summary.lines, key = { it.categoryId }) { line ->
                        val e = u.entities.first { it.budget.categoryId == line.categoryId }
                        BudgetRow(e, line, u.summary.finished, { editing = e }, { deleting = e })
                    }
                }
            }
            if (u != null && u.unbudgeted.isNotEmpty()) {
                item { SectionHeader("Spending without a budget") }
                item {
                    AppCard(contentPadding = 0.dp) {
                        Text("${Money.format(u.summary.unbudgetedMinor)} spent in categories with no limit this month.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp))
                        u.unbudgeted.forEach { (c, v) ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(AppIcons.forKey(c.iconKey), Color(c.colorArgb), 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                                    Text(Money.format(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { adding = c.id }) { Text("Set budget") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (months.size > 1 || (months.isNotEmpty() && months.first() != Dates.ymKey(month))) {
                item { SectionHeader("Budget history") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        months.forEach { key -> val ym = Dates.ymFromKey(key); FilterChip(ym == month, { vm.month.value = ym }, { Text(Dates.formatMonth(ym)) }) }
                    }
                }
            }
        }
    }
    val budgets = ui?.entities.orEmpty()
    if (adding != null || editing != null) {
        val e = editing
        BudgetDialog(e, adding?.takeIf { it > 0 }, categories.filter { c -> e?.budget?.categoryId == c.id || budgets.none { it.budget.categoryId == c.id } },
            { adding = null; editing = null }) { cat, limit -> vm.save(e?.budget?.id, cat, limit) }
    }
    deleting?.let { b -> ConfirmDialog("Delete budget?", "The ${b.categoryName} budget for ${Dates.formatMonth(month)} will be removed. Transactions are not affected.", "Delete", { vm.delete(b.budget.id) }, { deleting = null }) }
}

@Composable
private fun statusColor(s: BudgetStatus): Color {
    val fin = FinFoxTheme.colors
    return when (s) { BudgetStatus.ON_TRACK -> fin.income; BudgetStatus.AT_RISK -> fin.warning; BudgetStatus.OVER -> fin.expense }
}

/** The month at a glance: ring with % used, what's left and per day, the month-end forecast, and category counts. */
@Composable
private fun Overview(s: BudgetSummary) {
    val color = statusColor(s.status)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UsageRing(s.usedPercent, color)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.finished) "Spent in ${Dates.formatMonth(s.month)}" else "Spent so far", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(s.totalSpentMinor), style = MaterialTheme.typography.titleLarge)
                Text("of ${Money.format(s.totalLimitMinor)} budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(headline(s), style = MaterialTheme.typography.bodyMedium, color = color)
        Spacer(Modifier.height(12.dp))
        Row {
            Stat(if (s.remainingMinor >= 0) "Left" else "Over by", Money.format(kotlin.math.abs(s.remainingMinor)), Modifier.weight(1f),
                if (s.remainingMinor < 0) FinFoxTheme.colors.expense else null)
            if (!s.finished) {
                Stat("Per day left", Money.format(s.perDayLeftMinor), Modifier.weight(1f))
                Stat("Month-end forecast", Money.format(s.projectedMinor), Modifier.weight(1f), if (s.projectedMinor > s.totalLimitMinor) FinFoxTheme.colors.warning else null)
            } else {
                Stat("Budgets kept", "${s.onTrack} of ${s.lines.size}", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.onTrack > 0) Chip("${s.onTrack} on track", statusColor(BudgetStatus.ON_TRACK))
            if (s.atRisk > 0) Chip("${s.atRisk} spending fast", statusColor(BudgetStatus.AT_RISK))
            if (s.over > 0) Chip("${s.over} over", statusColor(BudgetStatus.OVER))
        }
    }
}

private fun headline(s: BudgetSummary): String = when {
    s.finished && s.remainingMinor >= 0 -> "You stayed within budget, ${Money.format(s.remainingMinor)} (${100 - s.usedPercent}%) under."
    s.finished -> "You went ${Money.format(-s.remainingMinor)} (${s.usedPercent - 100}%) over budget."
    s.status == BudgetStatus.OVER -> "Over budget by ${Money.format(-s.remainingMinor)} (${s.usedPercent - 100}%) with ${s.daysLeft} days to go."
    s.status == BudgetStatus.AT_RISK -> "At this pace you'll spend ${Money.format(s.projectedMinor)}, ${Money.format(s.projectedMinor - s.totalLimitMinor)} over budget. Keep to ${Money.format(s.perDayLeftMinor)} a day."
    else -> "On track. You can spend ${Money.format(s.perDayLeftMinor)} a day for the next ${s.daysLeft} days."
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun UsageRing(percent: Int, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, false, style = stroke)
            drawArc(color, -90f, 360f * (percent.coerceIn(0, 100) / 100f), false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.titleMedium)
            Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BudgetRow(b: BudgetWithSpent, line: BudgetLine, finished: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val fin = FinFoxTheme.colors
    val color = statusColor(line.status)
    AppCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(AppIcons.forKey(b.categoryIconKey), Color(b.categoryColor), 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.categoryName, style = MaterialTheme.typography.titleSmall)
                Text("${Money.format(line.spentMinor)} of ${Money.format(line.limitMinor)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${line.usedPercent}%", style = MaterialTheme.typography.titleSmall, color = color)
                Text(
                    when { line.vsLimitPercent > 0 -> "${line.vsLimitPercent}% above"; line.vsLimitPercent < 0 -> "${-line.vsLimitPercent}% below"; else -> "at limit" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete budget", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(line.spentMinor.toFloat() / line.limitMinor, color)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    line.status == BudgetStatus.OVER -> "Exceeded by ${Money.format(-line.remainingMinor)}"
                    finished -> "${Money.format(line.remainingMinor)} unspent"
                    line.status == BudgetStatus.AT_RISK -> "Spending fast: on pace for ${Money.format(line.projectedMinor)}. Keep to ${Money.format(line.perDayLeftMinor)}/day"
                    else -> "${Money.format(line.remainingMinor)} left · ${Money.format(line.perDayLeftMinor)}/day"
                },
                style = MaterialTheme.typography.labelMedium, color = if (line.status == BudgetStatus.ON_TRACK) MaterialTheme.colorScheme.onSurfaceVariant else color,
                modifier = Modifier.weight(1f),
            )
            line.vsLastMonthPercent?.let { pct ->
                Spacer(Modifier.width(8.dp))
                // Spending more than last month is drawn in the warning colour, less in green.
                val up = pct > 0
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background((if (up) fin.warning else fin.income).copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (up) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown, null, tint = if (up) fin.warning else fin.income, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${kotlin.math.abs(pct)}% vs last month", style = MaterialTheme.typography.labelSmall, color = if (up) fin.warning else fin.income)
                }
            }
        }
    }
}

@Composable
private fun BudgetDialog(existing: BudgetWithSpent?, preselect: Long?, categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (Long?, Long) -> Unit) {
    var categoryId by remember { mutableStateOf(existing?.budget?.categoryId ?: preselect) }
    var amount by remember { mutableStateOf(existing?.budget?.limitMinor?.let(Money::toInput) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add budget" else "Edit budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownField("Category", categories, categories.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id },
                    leading = { Icon(AppIcons.forKey(it.iconKey), null, tint = Color(it.colorArgb)) })
                AmountField(amount, { amount = it; error = null }, "Monthly limit", error = error)
                Text("Only expenses in this category count toward the budget. Transfers, card bill payments and loans don't.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = Money.parse(amount)) {
                    is Money.ParseResult.Ok -> if (categoryId == null) error = "Select a category" else { onSave(categoryId, r.minor); onDismiss() }
                    is Money.ParseResult.Error -> error = r.message
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
