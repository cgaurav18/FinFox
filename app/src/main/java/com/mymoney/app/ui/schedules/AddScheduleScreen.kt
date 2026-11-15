package com.mymoney.app.ui.schedules

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
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PrimaryButton

private val reminderOptions = listOf(0, 1, 2, 3, 5, 7)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddScheduleScreen(nav: NavController, vm: AddScheduleViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(title = if (f.id == null) "Add Schedule" else "Edit Schedule", onBack = { nav.popBackStack() }) { pad ->
        if (!f.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val usable = accounts.filter { !it.account.archived || it.account.id == f.accountId || it.account.id == f.toAccountId }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleCategory.entries.forEach { c ->
                    FilterChip(f.category == c, { vm.setCategory(c) }, { Text(c.label) }, leadingIcon = { Icon(AppIcons.forKey(c.iconKey), null) })
                }
            }
            AppTextField(f.title, { v -> vm.edit { it.copy(title = v, titleError = null) } }, "Title", error = f.titleError, placeholder = "e.g. Car EMI", capitalization = KeyboardCapitalization.Words, maxLength = 50)
            AmountField(f.amount, { v -> vm.edit { it.copy(amount = v, amountError = null) } }, error = f.amountError)
            val fromOptions = usable.filter { !f.isTransfer || it.account.type != AccountType.CREDIT_CARD }
            DropdownField("Payment account", fromOptions, fromOptions.firstOrNull { it.account.id == f.accountId }, { it.account.name },
                { a -> vm.edit { it.copy(accountId = a.account.id) } }, leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) })
            if (f.category != ScheduleCategory.CREDIT_CARD_BILL) {
                LabeledSwitchRow("Moves money to my own account", "e.g. SIP into an investment account. Counted as a transfer, not an expense.", f.isTransfer, { on -> vm.edit { it.copy(isTransfer = on, toAccountId = null) } })
            }
            if (f.isTransfer) {
                val toOptions = usable.filter { it.account.id != f.accountId && (f.category != ScheduleCategory.CREDIT_CARD_BILL || it.account.type == AccountType.CREDIT_CARD) }
                DropdownField(if (f.category == ScheduleCategory.CREDIT_CARD_BILL) "Credit card to pay" else "To account", toOptions, toOptions.firstOrNull { it.account.id == f.toAccountId }, { it.account.name },
                    { a -> vm.edit { it.copy(toAccountId = a.account.id) } }, leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) },
                    placeholder = if (toOptions.isEmpty()) "Add a credit card first" else "Select")
            } else {
                DropdownField("Expense category", categories, categories.firstOrNull { it.id == f.expenseCategoryId }, { it.name },
                    { c -> vm.edit { it.copy(expenseCategoryId = c.id) } }, leading = { Icon(AppIcons.forKey(it.iconKey), null, tint = Color(it.colorArgb)) },
                    placeholder = "Automatic from type")
            }
            DropdownField("Frequency", Frequency.entries, f.frequency, { it.label }, { v -> vm.edit { it.copy(frequency = v) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Start date", f.start, { d -> vm.edit { it.copy(start = d) } }, Modifier.weight(1f))
                DateField("End date (optional)", f.end, { d -> vm.edit { it.copy(end = d) } }, Modifier.weight(1f), onClear = { vm.edit { it.copy(end = null) } }, minDate = f.start)
            }
            DateField("Next due date", f.computedNextDue, { d -> vm.edit { it.copy(nextDue = d, nextDueTouched = true) } }, minDate = f.start, maxDate = f.end)
            DropdownField("Reminder", reminderOptions, f.reminderDays, { if (it == 0) "On the due date" else "$it day${if (it > 1) "s" else ""} before" }, { v -> vm.edit { it.copy(reminderDays = v) } })
            AppTextField(f.notes, { v -> vm.edit { it.copy(notes = v) } }, "Notes (optional)", singleLine = false, maxLength = 200)
            AppCard(contentPadding = 12.dp) {
                LabeledSwitchRow("Active", "Paused schedules don't send reminders", f.active, { on -> vm.edit { it.copy(active = on) } })
            }
            Text("Money is never deducted automatically. You'll be reminded, then mark each installment as paid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(if (f.id == null) "Save" else "Update", vm::save, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}
