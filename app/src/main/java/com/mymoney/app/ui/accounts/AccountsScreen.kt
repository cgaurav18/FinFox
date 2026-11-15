package com.mymoney.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.ListRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.StatTile
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinanceTheme

fun accountTypeLabel(t: AccountType?) = when (t) { null -> "All"; AccountType.BANK -> "Bank"; AccountType.CREDIT_CARD -> "Card"; AccountType.CASH -> "Cash" }

@Composable
fun AccountsScreen(nav: NavController, vm: AccountsViewModel = hiltViewModel()) {
    val list by vm.accounts.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val showArchived by vm.showArchived.collectAsStateWithLifecycle()
    val archivedCount by vm.archivedCount.collectAsStateWithLifecycle()
    AppScaffold(
        title = "Accounts", onBack = { nav.popBackStack() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.accountEdit(type = tab?.name)) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add Account") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        val items = list
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PillTabs(listOf(null, AccountType.BANK, AccountType.CREDIT_CARD, AccountType.CASH), tab, ::accountTypeLabel, { vm.tab.value = it }) }
            totals?.let { t ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Available", t.availableMinor, FinanceTheme.colors.income, Icons.Filled.AccountBalance, Modifier.weight(1f))
                        StatTile("Card outstanding", t.creditOutstandingMinor, FinanceTheme.colors.expense, AppIcons.forAccount(AccountType.CREDIT_CARD), Modifier.weight(1f))
                    }
                }
            }
            when {
                items == null -> item { LoadingState() }
                items.isEmpty() -> item {
                    EmptyState(AppIcons.forAccount(tab ?: AccountType.BANK), "No ${accountTypeLabel(tab).lowercase().takeIf { tab != null } ?: ""} accounts".replace("  ", " "),
                        "Add your bank accounts, credit cards and cash wallets to track balances.", action = "Add account") { nav.navigate(Routes.accountEdit(type = tab?.name)) }
                }
                else -> item { AppCard(contentPadding = 0.dp) { items.forEach { AccountRow(it) { nav.navigate(Routes.accountDetail(it.account.id)) } } } }
            }
            if (archivedCount > 0) item {
                TextButton(onClick = { vm.showArchived.value = !showArchived }) { Text(if (showArchived) "Hide archived accounts" else "Show $archivedCount archived account(s)") }
            }
        }
    }
}

@Composable
fun AccountRow(a: AccountWithBalance, onClick: () -> Unit) {
    val acc = a.account
    val subtitle = buildString {
        if (acc.type == AccountType.CASH) append(acc.description ?: "On hand")
        else {
            append(acc.bankName ?: "")
            acc.last4?.let { append(" •••• $it") }
            if (acc.type == AccountType.CREDIT_CARD && acc.creditLimitMinor != null) append(" • Limit ${Money.compact(acc.creditLimitMinor, acc.currency)}")
        }
    }
    ListRow(AppIcons.forAccount(acc.type), Color(acc.colorArgb), acc.name, subtitle, onClick = onClick) {
        Column(horizontalAlignment = Alignment.End) {
            if (acc.type == AccountType.CREDIT_CARD) {
                MoneyText(a.outstandingMinor, color = if (a.outstandingMinor > 0) FinanceTheme.colors.expense else FinanceTheme.colors.income, style = MaterialTheme.typography.titleSmall, currency = acc.currency)
                Text("outstanding", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                MoneyText(a.balanceMinor, color = if (a.balanceMinor < 0) FinanceTheme.colors.expense else FinanceTheme.colors.income, style = MaterialTheme.typography.titleSmall, currency = acc.currency)
            }
            if (acc.archived) Chip("Archived", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
