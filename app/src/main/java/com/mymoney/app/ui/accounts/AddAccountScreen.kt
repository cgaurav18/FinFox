package com.mymoney.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.ColorPicker
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton

private val bankAccountTypes = listOf("Savings", "Current", "Salary", "NRE", "NRO", "Joint", "Other")

@Composable
fun AddAccountScreen(nav: NavController, vm: AddAccountViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    val title = when { f.id != null -> "Edit Account"; f.type == AccountType.CREDIT_CARD -> "Add Credit Card"; f.type == AccountType.CASH -> "Add Cash Wallet"; else -> "Add Bank Account" }
    AppScaffold(title = title, onBack = { nav.popBackStack() }) { pad ->
        if (!f.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (f.id == null) PillTabs(AccountType.entries, f.type, { when (it) { AccountType.BANK -> "Bank"; AccountType.CREDIT_CARD -> "Credit Card"; AccountType.CASH -> "Cash" } }, vm::setType)
            when (f.type) {
                AccountType.BANK -> {
                    AppTextField(f.bankName, { v -> vm.edit { it.copy(bankName = v) } }, "Bank name", error = f.errors["bank"], placeholder = "e.g. HDFC Bank", capitalization = KeyboardCapitalization.Words, maxLength = 40)
                    AppTextField(f.name, { v -> vm.edit { it.copy(name = v) } }, "Account name", error = f.errors["name"], placeholder = "e.g. Salary Account", capitalization = KeyboardCapitalization.Words, maxLength = 40)
                    AppTextField(f.last4, { v -> if (v.all(Char::isDigit)) vm.edit { it.copy(last4 = v) } }, "Last 4 digits of account number (optional)", error = f.errors["last4"], keyboardType = KeyboardType.Number, maxLength = 4)
                    DropdownField("Account type", bankAccountTypes, f.bankAccountType, { it }, { v -> vm.edit { it.copy(bankAccountType = v) } })
                    AmountField(f.opening, { v -> vm.edit { it.copy(opening = v) } }, "Opening balance", error = f.errors["opening"], currency = f.currency)
                    DropdownField("Currency", Money.supportedCurrencies, f.currency, { it }, { v -> vm.edit { it.copy(currency = v) } })
                }
                AccountType.CREDIT_CARD -> {
                    AppTextField(f.name, { v -> vm.edit { it.copy(name = v) } }, "Card name", error = f.errors["name"], placeholder = "e.g. ICICI Amazon Pay", capitalization = KeyboardCapitalization.Words, maxLength = 40)
                    AppTextField(f.bankName, { v -> vm.edit { it.copy(bankName = v) } }, "Bank name", error = f.errors["bank"], capitalization = KeyboardCapitalization.Words, maxLength = 40)
                    AppTextField(f.last4, { v -> if (v.all(Char::isDigit)) vm.edit { it.copy(last4 = v) } }, "Last 4 digits (optional)", error = f.errors["last4"], keyboardType = KeyboardType.Number, maxLength = 4)
                    AmountField(f.creditLimit, { v -> vm.edit { it.copy(creditLimit = v) } }, "Credit limit", error = f.errors["limit"], currency = f.currency)
                    AmountField(f.opening, { v -> vm.edit { it.copy(opening = v) } }, "Current outstanding", error = f.errors["opening"], currency = f.currency)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(f.statementDay, { v -> if (v.all(Char::isDigit)) vm.edit { it.copy(statementDay = v) } }, "Statement day", Modifier.weight(1f), error = f.errors["statement"], keyboardType = KeyboardType.Number, maxLength = 2, placeholder = "1-31")
                        AppTextField(f.dueDay, { v -> if (v.all(Char::isDigit)) vm.edit { it.copy(dueDay = v) } }, "Payment due day", Modifier.weight(1f), error = f.errors["due"], keyboardType = KeyboardType.Number, maxLength = 2, placeholder = "1-31")
                    }
                    Text("Never enter your full card number, CVV or PIN. My Money only needs the last 4 digits to tell cards apart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AccountType.CASH -> {
                    AppTextField(f.name, { v -> vm.edit { it.copy(name = v) } }, "Wallet name", error = f.errors["name"], placeholder = "e.g. Cash Wallet", capitalization = KeyboardCapitalization.Words, maxLength = 40)
                    AmountField(f.opening, { v -> vm.edit { it.copy(opening = v) } }, "Opening cash balance", error = f.errors["opening"], currency = f.currency)
                    AppTextField(f.description, { v -> vm.edit { it.copy(description = v) } }, "Description (optional)", maxLength = 80)
                }
            }
            if (f.id != null && f.opening != f.originalOpening) {
                AppTextField(f.reason, { v -> vm.edit { it.copy(reason = v) } }, "Reason for changing the opening amount", placeholder = "Saved to the audit trail", maxLength = 80)
            }
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ColorPicker(f.color, { c -> vm.edit { it.copy(color = c) } })
            Spacer(Modifier.height(4.dp))
            PrimaryButton(if (f.id == null) "Save" else "Update", vm::save, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}
