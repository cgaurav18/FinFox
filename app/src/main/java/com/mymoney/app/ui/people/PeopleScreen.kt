package com.mymoney.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.LoanDetail
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalDate

@Composable
fun PeopleScreen(nav: NavController, vm: PeopleViewModel = hiltViewModel()) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val loans by vm.loans.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val t by vm.totals.collectAsStateWithLifecycle()
    val fin = FinFoxTheme.colors
    val currency = LocalCurrency.current
    AppScaffold(
        title = "Money with People", onBack = { nav.popBackStack() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.loanEdit(direction = if (tab == PeopleTab.RECEIVED) "RECEIVED" else "GAVE")) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text(if (tab == PeopleTab.RECEIVED) "I Received" else "I Gave") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppCard(Modifier.weight(1f), contentPadding = 12.dp) {
                        Text("You'll receive", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(t.lent, color = fin.income)
                        if (t.lentOverdue > 0) Text("${Money.format(t.lentOverdue, currency)} overdue", style = MaterialTheme.typography.labelSmall, color = fin.expense)
                    }
                    AppCard(Modifier.weight(1f), contentPadding = 12.dp) {
                        Text("You'll pay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(t.borrowed, color = fin.expense)
                        if (t.borrowedOverdue > 0) Text("${Money.format(t.borrowedOverdue, currency)} overdue", style = MaterialTheme.typography.labelSmall, color = fin.expense)
                    }
                }
            }
            item { PillTabs(PeopleTab.entries, tab, { it.label }, { vm.tab.value = it }) }
            item { AppTextField(query, { vm.query.value = it }, "Search by person", leadingIcon = Icons.Filled.Search) }
            if (tab == PeopleTab.PEOPLE) {
                val list = people
                when {
                    list == null -> item { LoadingState() }
                    list.isEmpty() -> item { EmptyState(Icons.Filled.Groups, "No people yet", "People appear here once you record money given or received.") }
                    else -> item {
                        AppCard(contentPadding = 0.dp) {
                            list.forEach { p ->
                                val net = p.lentOutstandingMinor - p.borrowedOutstandingMinor
                                ListRow(Icons.Filled.Person, MaterialTheme.colorScheme.primary, p.name,
                                    when { net > 0 -> "Owes you"; net < 0 -> "You owe"; else -> "All settled" } + (p.phone?.let { " • $it" } ?: ""),
                                    onClick = { nav.navigate(Routes.person(p.personId)) }) {
                                    MoneyText(kotlin.math.abs(net), color = if (net > 0) fin.income else if (net < 0) fin.expense else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                val list = loans
                when {
                    list == null -> item { LoadingState() }
                    list.isEmpty() -> item {
                        EmptyState(if (tab == PeopleTab.GAVE) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived,
                            if (tab == PeopleTab.GAVE) "No money lent" else "No money borrowed",
                            if (tab == PeopleTab.GAVE) "Record money you lend so you remember to collect it. It's not counted as an expense." else "Record money you borrow to track what you owe. It's not counted as income.",
                            action = "Add entry") { nav.navigate(Routes.loanEdit(direction = if (tab == PeopleTab.GAVE) "GAVE" else "RECEIVED")) }
                    }
                    else -> item {
                        AppCard(contentPadding = 0.dp) {
                            list.forEachIndexed { i, l ->
                                LoanRow(l) { nav.navigate(Routes.loanDetail(l.loan.id)) }
                                if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoanRow(l: LoanDetail, onClick: () -> Unit) {
    val fin = FinFoxTheme.colors
    val currency = LocalCurrency.current
    val today = Dates.today()
    val due = l.loan.dueEpochDay?.let(LocalDate::ofEpochDay)
    val overdue = !l.loan.settled && due != null && due.isBefore(today)
    val gave = l.loan.direction == LoanDirection.GAVE
    val sub = buildString {
        append(Money.format(l.loan.amountMinor, currency))
        if (l.repaidMinor > 0) append(" • repaid ${Money.format(l.repaidMinor, currency)}")
        if (!l.loan.settled && due != null) append(" • ${Dates.dueLabel(due, today)}")
    }
    ListRow(Icons.Filled.Person, if (overdue) fin.expense else if (gave) fin.income else fin.warning, l.personName, sub, onClick = onClick) {
        Column(horizontalAlignment = Alignment.End) {
            if (l.loan.settled) Chip("Settled", fin.income)
            else {
                MoneyText(l.outstandingMinor, color = if (gave) fin.income else fin.expense, style = MaterialTheme.typography.titleSmall)
                Text("outstanding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
