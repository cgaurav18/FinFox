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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.MoneyText
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

@Composable
fun PersonScreen(nav: NavController, vm: PersonViewModel = hiltViewModel()) {
    val p by vm.person.collectAsStateWithLifecycle()
    val loans by vm.loans.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    val fin = FinFoxTheme.colors
    AppScaffold(
        title = p?.name ?: "Person", onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { edit = true }) { Icon(Icons.Filled.Edit, "Edit contact") }
            if (loans?.isEmpty() == true) IconButton(onClick = { del = true }) { Icon(Icons.Filled.Delete, "Delete person") }
        },
    ) { pad ->
        val person = p ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val list = loans ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val owesMe = list.filter { it.loan.direction == LoanDirection.GAVE }.sumOf { it.outstandingMinor }
        val iOwe = list.filter { it.loan.direction == LoanDirection.RECEIVED }.sumOf { it.outstandingMinor }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppCard(Modifier.weight(1f), contentPadding = 12.dp) {
                        Text("${person.name.substringBefore(' ')} owes you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(owesMe, color = fin.income)
                    }
                    AppCard(Modifier.weight(1f), contentPadding = 12.dp) {
                        Text("You owe", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(iOwe, color = fin.expense)
                    }
                }
            }
            person.phone?.let { item { Text("Phone: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("I Gave", { nav.navigate(Routes.loanEdit(direction = "GAVE", person = person.name)) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.CallMade)
                    TonalButton("I Received", { nav.navigate(Routes.loanEdit(direction = "RECEIVED", person = person.name)) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.CallReceived)
                }
            }
            item { SectionHeader("Ledger") }
            item {
                AppCard(contentPadding = 0.dp) {
                    if (list.isEmpty()) Text("No entries.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.forEach { l ->
                        Column {
                            LoanRow(l) { nav.navigate(Routes.loanDetail(l.loan.id)) }
                            Text(if (l.loan.direction == LoanDirection.GAVE) "   You gave" else "   You received", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 70.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        }
    }
    if (edit) p?.let { person ->
        var name by remember { mutableStateOf(person.name) }
        var phone by remember { mutableStateOf(person.phone ?: "") }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Edit contact") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AppTextField(name, { name = it }, "Name"); AppTextField(phone, { phone = it }, "Phone (optional)", keyboardType = KeyboardType.Phone) } },
            confirmButton = { TextButton(onClick = { vm.update(name, phone); edit = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } },
        )
    }
    if (del) ConfirmDialog("Remove person?", "This contact has no entries and will be removed.", "Remove", vm::delete, { del = false })
}
