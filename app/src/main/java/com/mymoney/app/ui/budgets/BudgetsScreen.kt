package com.mymoney.app.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.BudgetWithSpent
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.repo.BudgetRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.MessageBus
import com.mymoney.app.ui.components.MonthSelector
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetsViewModel @Inject constructor(private val repo: BudgetRepository, categories: CategoryRepository, bus: MessageBus) : BaseViewModel(bus) {
    val month = MutableStateFlow(YearMonth.now())
    val budgets = month.flatMapLatest { repo.observeMonth(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val months = repo.observeMonths().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.observe(CategoryType.EXPENSE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(id: Long?, categoryId: Long?, limit: Long) = launchSafe(if (id == null) "Budget added" else "Budget updated") { repo.save(id, categoryId, month.value, limit) }
    fun delete(id: Long) = launchSafe("Budget deleted") { repo.delete(id) }
    fun copyPrevious() = launchSafe { val n = repo.copyFromPrevious(month.value); bus.show(if (n == 0) "All budgets already exist this month" else "Copied $n budget(s)") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BudgetsScreen(nav: NavController, vm: BudgetsViewModel = hiltViewModel()) {
    val month by vm.month.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BudgetWithSpent?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BudgetWithSpent?>(null) }
    val currency = LocalCurrency.current
    val fin = FinanceTheme.colors
    AppScaffold(
        title = "Budgets", onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = vm::copyPrevious) { Icon(Icons.Filled.ContentCopy, "Copy last month's budgets") } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add Budget") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { MonthSelector(month, { vm.month.value = it }) } }
            val list = budgets
            when {
                list == null -> item { LoadingState() }
                list.isEmpty() -> item {
                    EmptyState(Icons.Filled.PieChart, "No budgets for ${Dates.formatMonth(month)}", "Set monthly limits per category. You'll be alerted at 80%, 90% and 100%.",
                        action = "Add budget") { adding = true }
                }
                else -> {
                    val total = list.sumOf { it.budget.limitMinor }
                    val spent = list.sumOf { it.spentMinor }
                    item {
                        AppCard {
                            Text("Total budget", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${Money.format(spent, currency)} of ${Money.format(total, currency)}", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            val p = Money.percent(spent, total)
                            ProgressBar(spent.toFloat() / total.coerceAtLeast(1), barColor(p, fin.income, fin.warning, fin.expense))
                            Spacer(Modifier.height(6.dp))
                            Text(if (spent <= total) "${Money.format(total - spent, currency)} left • $p% used" else "Over by ${Money.format(spent - total, currency)}", style = MaterialTheme.typography.bodySmall,
                                color = if (spent > total) fin.expense else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.budget.id }) { b -> BudgetRow(b, { editing = b }, { deleting = b }) }
                }
            }
            if (months.size > 1 || (months.isNotEmpty() && months.first() != Dates.ymKey(month))) {
                item { SectionHeader("Budget history") }
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        months.forEach { key -> val ym = Dates.ymFromKey(key); FilterChip(ym == month, { vm.month.value = ym }, { Text(Dates.formatMonth(ym)) }) }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val e = editing
        BudgetDialog(e, categories.filter { c -> e?.budget?.categoryId == c.id || budgets.orEmpty().none { it.budget.categoryId == c.id } }, { adding = false; editing = null }) { cat, limit -> vm.save(e?.budget?.id, cat, limit) }
    }
    deleting?.let { b -> ConfirmDialog("Delete budget?", "The ${b.categoryName} budget for ${Dates.formatMonth(month)} will be removed. Transactions are not affected.", "Delete", { vm.delete(b.budget.id) }, { deleting = null }) }
}

private fun barColor(p: Int, ok: Color, warn: Color, bad: Color) = when { p >= 100 -> bad; p >= 80 -> warn; else -> ok }

@Composable
private fun BudgetRow(b: BudgetWithSpent, onEdit: () -> Unit, onDelete: () -> Unit) {
    val currency = LocalCurrency.current
    val fin = FinanceTheme.colors
    val p = Money.percent(b.spentMinor, b.budget.limitMinor)
    AppCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(AppIcons.forKey(b.categoryIconKey), Color(b.categoryColor), 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.categoryName, style = MaterialTheme.typography.titleSmall)
                Text("${Money.format(b.spentMinor, currency)} of ${Money.format(b.budget.limitMinor, currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$p%", style = MaterialTheme.typography.titleSmall, color = barColor(p, fin.income, fin.warning, fin.expense))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete budget", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(b.spentMinor.toFloat() / b.budget.limitMinor, barColor(p, fin.income, fin.warning, fin.expense))
        Spacer(Modifier.height(6.dp))
        val remaining = b.budget.limitMinor - b.spentMinor
        Text(if (remaining >= 0) "${Money.format(remaining, currency)} remaining" else "Exceeded by ${Money.format(-remaining, currency)}",
            style = MaterialTheme.typography.labelMedium, color = if (remaining < 0) fin.expense else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BudgetDialog(existing: BudgetWithSpent?, categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (Long?, Long) -> Unit) {
    var categoryId by remember { mutableStateOf(existing?.budget?.categoryId) }
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
