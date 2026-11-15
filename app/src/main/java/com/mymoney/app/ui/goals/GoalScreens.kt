package com.mymoney.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.GoalWithSaved
import com.mymoney.app.ui.components.AmountEntryDialog
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.ColorPicker
import com.mymoney.app.ui.components.ConfirmDialog
import com.mymoney.app.ui.components.DateField
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.KeyValueRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.LocalCurrency
import com.mymoney.app.ui.components.PillTabs
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.ProgressBar
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun GoalsScreen(nav: NavController, vm: GoalsViewModel = hiltViewModel()) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    val archived by vm.archived.collectAsStateWithLifecycle()
    AppScaffold(
        title = "Savings Goals", onBack = { nav.popBackStack() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.goalEdit()) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New Goal") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PillTabs(listOf(false, true), archived, { if (it) "Archived" else "Active" }, { vm.archived.value = it }) }
            val list = goals
            when {
                list == null -> item { LoadingState() }
                list.isEmpty() -> item {
                    EmptyState(Icons.Filled.Savings, if (archived) "No archived goals" else "No savings goals yet", "Set aside money for a trip, gadget or emergency fund and track your progress.",
                        action = if (archived) null else "Create goal") { nav.navigate(Routes.goalEdit()) }
                }
                else -> {
                    item {
                        val saved = list.sumOf { it.savedMinor }; val target = list.sumOf { it.goal.targetMinor }
                        Text("${Money.format(saved, LocalCurrency.current)} saved of ${Money.format(target, LocalCurrency.current)} across ${list.size} goal(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(list, key = { it.goal.id }) { g -> GoalCard(g) { nav.navigate(Routes.goalDetail(g.goal.id)) } }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(g: GoalWithSaved, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val done = g.savedMinor >= g.goal.targetMinor
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(AppIcons.forKey(g.goal.iconKey), Color(g.goal.colorArgb), 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.goal.name, style = MaterialTheme.typography.titleSmall)
                Text(g.goal.targetEpochDay?.let { "By ${Dates.formatDay(LocalDate.ofEpochDay(it))}" } ?: "No deadline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done) Chip("Completed", FinFoxTheme.colors.income) else Text("${Money.percent(g.savedMinor, g.goal.targetMinor)}%", style = MaterialTheme.typography.titleSmall, color = Color(g.goal.colorArgb))
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(g.savedMinor.toFloat() / g.goal.targetMinor, Color(g.goal.colorArgb))
        Spacer(Modifier.height(6.dp))
        Text("${Money.format(g.savedMinor, currency)} of ${Money.format(g.goal.targetMinor, currency)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AddGoalScreen(nav: NavController, vm: AddGoalViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(title = if (f.id == null) "New Goal" else "Edit Goal", onBack = { nav.popBackStack() }) { pad ->
        if (!f.loaded) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTextField(f.name, { v -> vm.edit { it.copy(name = v, nameError = null) } }, "Goal name", error = f.nameError, placeholder = "e.g. Goa trip", capitalization = KeyboardCapitalization.Words, maxLength = 40)
            AmountField(f.target, { v -> vm.edit { it.copy(target = v, targetError = null) } }, "Target amount", error = f.targetError)
            DateField("Target date (optional)", f.targetDate, { d -> vm.edit { it.copy(targetDate = d) } }, onClear = { vm.edit { it.copy(targetDate = null) } }, minDate = Dates.today())
            if (f.id == null) AmountField(f.initial, { v -> vm.edit { it.copy(initial = v) } }, "Already saved (optional)")
            DropdownField("Linked account (optional)", listOf<Long?>(null) + accounts.map { it.account.id }, f.linkedAccountId,
                { id -> accounts.firstOrNull { it.account.id == id }?.let { "${it.account.name} • ${Money.format(it.balanceMinor, it.account.currency)}" } ?: "Not linked" },
                { v -> vm.edit { it.copy(linkedAccountId = v) } })
            Text("Goals earmark money you already hold — contributions don't move money or change balances, so nothing is counted twice. A linked account can't have more earmarked than its balance.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ColorPicker(f.color, { c -> vm.edit { it.copy(color = c) } })
            PrimaryButton(if (f.id == null) "Create goal" else "Update", vm::save, loading = busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun GoalDetailScreen(nav: NavController, vm: GoalDetailViewModel = hiltViewModel()) {
    val g by vm.goal.collectAsStateWithLifecycle()
    val list by vm.contributions.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf(false) }
    var withdraw by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var delEntry by remember { mutableStateOf<Long?>(null) }
    val currency = LocalCurrency.current
    val fin = FinFoxTheme.colors
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }
    AppScaffold(
        title = g?.goal?.name ?: "Goal", onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { nav.navigate(Routes.goalEdit(vm.id)) }) { Icon(Icons.Filled.Edit, "Edit") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                val arch = g?.goal?.archived == true
                DropdownMenuItem({ Text(if (arch) "Restore" else "Archive") }, { menu = false; vm.setArchived(!arch) }, leadingIcon = { Icon(if (arch) Icons.Filled.Unarchive else Icons.Filled.Archive, null) })
                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; del = true }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        },
    ) { pad ->
        val d = g ?: run { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        val color = Color(d.goal.colorArgb)
        val remaining = (d.goal.targetMinor - d.savedMinor).coerceAtLeast(0)
        val target = d.goal.targetEpochDay?.let(LocalDate::ofEpochDay)
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(d.savedMinor, currency), style = MaterialTheme.typography.headlineMedium, color = color)
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(d.savedMinor.toFloat() / d.goal.targetMinor, color, height = 12.dp)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("${Money.percent(d.savedMinor, d.goal.targetMinor)}% of ${Money.format(d.goal.targetMinor, currency)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (remaining == 0L) Chip("Goal reached!", fin.income) else Text("${Money.format(remaining, currency)} to go", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (target != null && remaining > 0) {
                        val months = ChronoUnit.MONTHS.between(Dates.today().withDayOfMonth(1), target.withDayOfMonth(1)).coerceAtLeast(1)
                        Spacer(Modifier.height(8.dp))
                        Text(if (target.isBefore(Dates.today())) "Target date passed (${Dates.formatDay(target)})" else "Save about ${Money.format((remaining + months - 1) / months, currency)} per month to reach it by ${Dates.formatDay(target)}",
                            style = MaterialTheme.typography.bodySmall, color = if (target.isBefore(Dates.today())) fin.expense else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (!d.goal.archived) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Add money", { add = true }, Modifier.weight(1f), icon = Icons.Filled.Add)
                    SecondaryButton("Withdraw", { withdraw = true }, Modifier.weight(1f), icon = Icons.Filled.Remove, enabled = d.savedMinor > 0)
                }
            }
            item {
                AppCard {
                    KeyValueRow("Target", Money.format(d.goal.targetMinor, currency))
                    KeyValueRow("Target date", target?.let(Dates::formatDay) ?: "Not set")
                    KeyValueRow("Linked account", d.linkedAccountName ?: "Not linked")
                    KeyValueRow("Status", when { d.goal.archived -> "Archived"; remaining == 0L -> "Completed"; else -> "In progress" })
                }
            }
            item { SectionHeader("Savings history") }
            item {
                AppCard {
                    val l = list
                    when {
                        l == null -> LoadingState()
                        l.isEmpty() -> Text("No contributions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> l.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Money.format(c.amountMinor, currency, signed = true), style = MaterialTheme.typography.titleSmall, color = if (c.amountMinor >= 0) fin.income else fin.expense)
                                    Text(Dates.formatDay(c.dateMillis) + (c.note?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { delEntry = c.id }) { Icon(Icons.Filled.Delete, "Remove entry", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (add) AmountEntryDialog("Add to goal", "Add", { add = false }, { a, d, n -> vm.contribute(a, d, n) })
    if (withdraw) AmountEntryDialog("Withdraw from goal", "Withdraw", { withdraw = false }, { a, d, n -> vm.withdraw(a, d, n) }, helper = "Releases earmarked money back to general use. Account balances don't change.")
    if (del) ConfirmDialog("Delete goal?", "The goal and its savings history will be deleted. Account balances are not affected.", "Delete", vm::delete, { del = false })
    delEntry?.let { id -> ConfirmDialog("Remove entry?", "This contribution/withdrawal will be removed from the goal.", "Remove", { vm.deleteContribution(id) }, { delEntry = null }) }
}
