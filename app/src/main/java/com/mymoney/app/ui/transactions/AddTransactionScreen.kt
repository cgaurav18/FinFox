package com.mymoney.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.TimeField
import com.mymoney.app.ui.navigation.Routes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTransactionScreen(nav: NavController, vm: AddTransactionViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val currency = LocalCurrency.current
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(title = if (f.id == null) "Add Transaction" else "Edit Transaction", onBack = { nav.popBackStack() }) { pad ->
        if (!f.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val usable = accounts.filter { !it.account.archived || it.account.id == f.accountId || it.account.id == f.toAccountId }
        if (usable.isEmpty()) {
            EmptyState(Icons.Filled.Storefront, "No accounts yet", "Add a bank account, card or cash wallet before recording transactions.", Modifier.padding(pad), "Add account") { nav.navigate(Routes.accountEdit()) }
            return@AppScaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillTabs(listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER), f.type, ::txnTypeLabel, vm::setType)
            AmountField(f.amount, { v -> vm.edit { it.copy(amount = v, amountError = null) } }, error = f.amountError)
            if (f.type != TxnType.TRANSFER) {
                DropdownField(
                    "Category", categories, categories.firstOrNull { it.id == f.categoryId }, { it.name }, { c -> vm.edit { it.copy(categoryId = c.id) } },
                    leading = { Icon(AppIcons.forKey(it.iconKey), null, tint = Color(it.colorArgb)) },
                )
            }
            Text(if (f.type == TxnType.TRANSFER) "From account" else "Payment method", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val methods = if (f.type == TxnType.TRANSFER) listOf(PayMethod.BANK, PayMethod.CASH) else PayMethod.entries
            PillTabs(methods, f.method, { it.label }, vm::setMethod)
            val fromOptions = usable.filter { it.account.type == f.method.type }
            DropdownField(
                "Account", fromOptions, fromOptions.firstOrNull { it.account.id == f.accountId },
                { "${it.account.name} • ${Money.format(if (it.account.type == AccountType.CREDIT_CARD) it.outstandingMinor else it.balanceMinor, it.account.currency)}${if (it.account.type == AccountType.CREDIT_CARD) " due" else ""}" },
                { a -> vm.edit { it.copy(accountId = a.account.id) } },
                leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) },
                placeholder = "No ${f.method.label.lowercase()} accounts",
            )
            if (f.type == TxnType.TRANSFER) {
                val toOptions = usable.filter { it.account.id != f.accountId }
                DropdownField(
                    "To account", toOptions, toOptions.firstOrNull { it.account.id == f.toAccountId },
                    { it.account.name + if (it.account.type == AccountType.CREDIT_CARD) " (pay card bill)" else "" },
                    { a -> vm.edit { it.copy(toAccountId = a.account.id) } },
                    leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) },
                )
                Text("Transfers move money between your accounts and are not counted as income or expense. Paying a credit card bill reduces both the bank balance and the card outstanding.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (f.method == PayMethod.CARD && f.type == TxnType.EXPENSE) {
                Text("Card purchases increase the card's outstanding. Paying the bill later is a transfer, so the spend is counted only once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Date", f.date, { d -> vm.edit { it.copy(date = d) } }, Modifier.weight(1f), maxDate = Dates.today().plusYears(1))
                TimeField("Time", f.time, { t -> vm.edit { it.copy(time = t) } }, Modifier.weight(1f))
            }
            AppTextField(f.merchant, { v -> vm.edit { it.copy(merchant = v) } }, if (f.type == TxnType.INCOME) "Payer (optional)" else "Merchant / person (optional)", leadingIcon = Icons.Filled.Storefront, capitalization = KeyboardCapitalization.Words, maxLength = 60)
            AppTextField(f.note, { v -> vm.edit { it.copy(note = v) } }, "Description (optional)", leadingIcon = Icons.AutoMirrored.Filled.Notes, singleLine = false, maxLength = 300)
            AppTextField(f.tags, { v -> vm.edit { it.copy(tags = v) } }, "Tags (comma separated)", leadingIcon = Icons.Filled.Sell, placeholder = "work, trip", maxLength = 120)
            if (f.id == null && f.type != TxnType.INCOME) AppCard(contentPadding = 12.dp) {
                LabeledSwitchRow("Repeat this payment", "Creates a scheduled payment with reminders", f.recurring != null, { on -> vm.edit { it.copy(recurring = if (on) Frequency.MONTHLY else null) } })
                if (f.recurring != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Frequency.entries.forEach { fr -> FilterChip(f.recurring == fr, { vm.edit { it.copy(recurring = fr) } }, { Text(fr.label) }) }
                }
            }
            if (f.linkedSchedule) Text("This transaction paid a scheduled installment. Editing it updates that payment record.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (f.id == null) "Save" else "Update", onClick = vm::save, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}
