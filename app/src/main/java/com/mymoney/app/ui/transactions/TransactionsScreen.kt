package com.mymoney.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.core.PeriodFilter
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.TxnSort
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(nav: NavController, vm: TransactionsViewModel = hiltViewModel()) {
    val f by vm.filter.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(f.query.isNotEmpty()) }
    var showFilters by remember { mutableStateOf(false) }
    val currency = LocalCurrency.current
    AppScaffold(
        title = "Transactions",
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) vm.update { it.copy(query = "") } }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search")
            }
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (f.activeCount > 0) Badge { Text("${f.activeCount}") } }) { Icon(Icons.Filled.FilterList, "Filters") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (searching) AppTextField(f.query, { q -> vm.update { it.copy(query = q) } }, "Search merchant, note, tag, person", leadingIcon = Icons.Filled.Search)
                PillTabs(listOf<TxnType?>(null, TxnType.INCOME, TxnType.EXPENSE, TxnType.TRANSFER), f.type, { it?.let(::txnTypeLabel) ?: "All" }, { t -> vm.update { it.copy(type = t) } })
                if (!s.loading) Row {
                    Text("${s.count} transaction${if (s.count == 1) "" else "s"}" + (f.period?.let { " • ${it.label}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (f.type != TxnType.TRANSFER) Text("In ${Money.format(s.income, currency)}  •  Out ${Money.format(s.expense, currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                s.loading -> LoadingState()
                s.count == 0 -> EmptyState(
                    Icons.Filled.ReceiptLong,
                    if (f.query.isNotEmpty() || f.activeCount > 0) "No matching transactions" else "No transactions yet",
                    if (f.query.isNotEmpty() || f.activeCount > 0) "Try a different search or clear filters." else "Tap + to record your first income or expense.",
                    action = if (f.activeCount > 0) "Clear filters" else "Add transaction",
                ) { if (f.activeCount > 0) vm.resetFilters() else nav.navigate(Routes.txnEdit(type = f.type?.name)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.groups.isNotEmpty()) s.groups.forEach { (date, list) ->
                        stickyHeader(key = "h$date") {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 6.dp)) {
                                Text(Dates.relativeDayLabel(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                val net = list.sumOf { when (it.txn.type) { TxnType.INCOME -> it.txn.amountMinor; TxnType.EXPENSE -> -it.txn.amountMinor; else -> 0 } }
                                if (net != 0L) Text(Money.format(net, currency, signed = true), style = MaterialTheme.typography.labelMedium, color = if (net > 0) FinanceTheme.colors.income else FinanceTheme.colors.expense)
                            }
                        }
                        item(key = "g$date") {
                            AppCard(contentPadding = 0.dp) {
                                list.forEachIndexed { i, d ->
                                    TxnRow(d, { nav.navigate(Routes.txnDetail(d.txn.id)) })
                                    if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    } else item {
                        AppCard(contentPadding = 0.dp) { s.flat.forEach { d -> TxnRow(d, { nav.navigate(Routes.txnDetail(d.txn.id)) }, showTime = false) } }
                    }
                }
            }
        }
    }
    if (showFilters) FilterSheet(vm) { showFilters = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(vm: TransactionsViewModel, onDismiss: () -> Unit) {
    val current by vm.filter.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(current.period) }
    var from by remember { mutableStateOf(current.custom?.start ?: Dates.today().withDayOfMonth(1)) }
    var to by remember { mutableStateOf(current.custom?.endInclusive ?: Dates.today()) }
    var accountId by remember { mutableStateOf(current.accountId) }
    var categoryId by remember { mutableStateOf(current.categoryId) }
    var min by remember { mutableStateOf(current.minAmount?.let(Money::toInput) ?: "") }
    var max by remember { mutableStateOf(current.maxAmount?.let(Money::toInput) ?: "") }
    var sort by remember { mutableStateOf(current.sort) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Text("Filter & sort", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.resetFilters(); onDismiss() }) { Text("Reset") } }
            Text("Date", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(period == null, { period = null }, { Text("All time") })
                PeriodFilter.entries.forEach { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
            }
            if (period == PeriodFilter.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("From", from, { from = it }, Modifier.weight(1f), maxDate = to)
                DateField("To", to, { to = it }, Modifier.weight(1f), minDate = from)
            }
            DropdownField("Account", listOf<Long?>(null) + accounts.map { it.account.id }, accountId, { id -> accounts.firstOrNull { it.account.id == id }?.account?.name ?: "All accounts" }, { accountId = it })
            DropdownField("Category", listOf<Long?>(null) + categories.map { it.id }, categoryId, { id -> categories.firstOrNull { it.id == id }?.let { "${it.name} (${it.type.name.lowercase()})" } ?: "All categories" }, { categoryId = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(min, { min = it }, "Min amount", Modifier.weight(1f))
                AmountField(max, { max = it }, "Max amount", Modifier.weight(1f))
            }
            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TxnSort.entries.forEach { o -> FilterChip(sort == o, { sort = o }, { Text(o.label) }) } }
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Apply", onClick = {
                vm.update {
                    it.copy(
                        period = period, custom = if (period == PeriodFilter.CUSTOM) DateRange(minOf(from, to), maxOf(from, to)) else null,
                        accountId = accountId, categoryId = categoryId, minAmount = Money.parseOrNull(min, allowZero = true),
                        maxAmount = Money.parseOrNull(max, allowZero = true), sort = sort,
                    )
                }
                onDismiss()
            })
            Spacer(Modifier.height(16.dp))
        }
    }
}
