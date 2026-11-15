package com.mymoney.app.ui.people

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLoanScreen(nav: NavController, vm: AddLoanViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    val gave = f.direction == LoanDirection.GAVE
    AppScaffold(title = if (f.id == null) "Add Loan Entry" else "Edit Loan Entry", onBack = { nav.popBackStack() }) { pad ->
        if (!f.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!f.hasRepayments) PillTabs(LoanDirection.entries, f.direction, { if (it == LoanDirection.GAVE) "I Gave Money" else "I Received Money" }, { d -> vm.edit { it.copy(direction = d) } })
            // Person name with suggestions from existing people.
            var expanded by remember { mutableStateOf(false) }
            val suggestions = people.filter { f.person.isNotBlank() && it.name.contains(f.person.trim(), true) && !it.name.equals(f.person.trim(), true) }.take(5)
            ExposedDropdownMenuBox(expanded = expanded && suggestions.isNotEmpty(), onExpandedChange = { expanded = it }) {
                AppTextField(f.person, { v -> vm.edit { it.copy(person = v, personError = null) }; expanded = true }, "Person name", Modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
                    error = f.personError, leadingIcon = Icons.Filled.Person, capitalization = KeyboardCapitalization.Words, maxLength = 50)
                ExposedDropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
                    suggestions.forEach { p -> DropdownMenuItem(text = { Text(p.name + (p.phone?.let { " • $it" } ?: "")) }, onClick = { vm.pickPerson(p.name, p.phone); expanded = false }) }
                }
            }
            AppTextField(f.phone, { v -> vm.edit { it.copy(phone = v) } }, "Phone number (optional)", leadingIcon = Icons.Filled.Phone, keyboardType = KeyboardType.Phone, maxLength = 20)
            AmountField(f.amount, { v -> vm.edit { it.copy(amount = v, amountError = null) } }, error = f.amountError)
            DropdownField(if (gave) "Paid from account" else "Received into account", accounts.filter { !it.account.archived || it.account.id == f.accountId },
                accounts.firstOrNull { it.account.id == f.accountId }, { it.account.name }, { a -> vm.edit { it.copy(accountId = a.account.id) } },
                leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Date", f.date, { d -> vm.edit { it.copy(date = d) } }, Modifier.weight(1f), maxDate = Dates.today())
                DateField("Due date (optional)", f.due, { d -> vm.edit { it.copy(due = d) } }, Modifier.weight(1f), onClear = { vm.edit { it.copy(due = null) } }, minDate = f.date)
            }
            AppTextField(f.description, { v -> vm.edit { it.copy(description = v) } }, "Description (optional)", singleLine = false, maxLength = 200)
            AppCard(contentPadding = 12.dp) {
                LabeledSwitchRow("Remind me", if (f.due == null) "Set a due date to get a reminder" else "A day before and when overdue", f.reminder && f.due != null, { on -> vm.edit { it.copy(reminder = on) } }, enabled = f.due != null)
            }
            Text(
                if (gave) "Your account balance decreases and the amount is tracked as money to receive. It isn't counted as an expense."
                else "Your account balance increases and the amount is tracked as money you owe. It isn't counted as income.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(if (f.id == null) "Save" else "Update", vm::save, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}
