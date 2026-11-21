package com.mymoney.app.ui.autocapture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymoney.app.autocapture.CaptureKind
import com.mymoney.app.autocapture.CapturePermissions
import com.mymoney.app.autocapture.CaptureRepository
import com.mymoney.app.autocapture.TxnDirection
import com.mymoney.app.autocapture.appLabel
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.CaptureStatus
import com.mymoney.app.data.db.CapturedMessageEntity
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.LabeledSwitchRow
import com.mymoney.app.ui.components.LoadingState
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.SecondaryButton
import com.mymoney.app.ui.components.SectionHeader
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.navigation.Routes
import com.mymoney.app.ui.theme.FinFoxTheme

@Composable
fun AutoCaptureScreen(nav: NavController, vm: AutoCaptureViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fin = FinFoxTheme.colors

    // Permission state changes outside the app (system settings), so re-read it whenever we come back.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    val hasSms = remember(resumeTick) { CapturePermissions.hasSms(context) }
    val canReadInbox = remember(resumeTick) { CapturePermissions.canReadInbox(context) }
    val hasListener = remember(resumeTick) { CapturePermissions.hasNotificationAccess(context) }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++ }

    AppScaffold(title = "Auto-capture payments", onBack = { nav.popBackStack() }) { pad ->
        val p = prefs
        if (p == null) { LoadingState(Modifier.padding(pad)); return@AppScaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "FinFox reads bank SMS, bank alert emails and UPI app notifications on this phone and records each payment once, even when the same payment is reported by all three. Nothing is uploaded anywhere.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledSwitchRow("Record payments automatically", if (p.enabled) "On" else "Off", p.enabled, { on ->
                        vm.update { it.copy(enabled = on) }
                        if (on && !hasSms) smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                    })
                }
            }

            item { SectionHeader("Sources") }
            item {
                AppCard(contentPadding = 0.dp) {
                    SourceRow(
                        Icons.Filled.Sms, "Bank SMS", "Debit and credit alerts from your bank. Most reliable source.",
                        granted = hasSms, enabled = p.readSms, onToggle = { on -> vm.update { it.copy(readSms = on) } },
                        actionLabel = "Allow", onAction = { smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SourceRow(
                        Icons.Filled.NotificationsActive, "UPI, bank & email notifications",
                        "Google Pay, PhonePe, Paytm, BHIM and bank apps, plus bank alert emails in Gmail or Outlook. Personal mail is never read.",
                        granted = hasListener, enabled = p.readNotifications, onToggle = { on -> vm.update { it.copy(readNotifications = on) } },
                        actionLabel = "Open settings", onAction = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && (!hasSms || !hasListener)) item {
                AppCard {
                    Text("Android blocks these permissions for apps installed from an APK file", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "If you see \"Restricted setting\", open App info, tap the ⋮ menu (top right), choose \"Allow restricted settings\", then come back and try again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Open App info") }
                }
            }

            item { SectionHeader("How entries are added") }
            item {
                AppCard(contentPadding = 12.dp) {
                    LabeledSwitchRow(
                        "Add without asking",
                        if (p.autoAdd) "Entries go straight into your transactions. You can edit any of them later."
                        else "Entries wait in the review list below until you confirm them.",
                        p.autoAdd, { on -> vm.update { it.copy(autoAdd = on) } },
                    )
                    LabeledSwitchRow("Notify me for each payment", "A silent notification to change the category quickly", p.notifyOnCapture, { on -> vm.update { it.copy(notifyOnCapture = on) } })
                    DropdownField(
                        "Default account", accounts, accounts.firstOrNull { it.account.id == p.defaultAccountId }, ::accountLabel,
                        { a -> vm.update { it.copy(defaultAccountId = a.account.id) } },
                        leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) },
                        placeholder = "Used when a message doesn't say which account",
                    )
                    Text(
                        "Tip: add the last 4 digits to each bank account and card (Accounts → edit) so FinFox can tell them apart from the SMS.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Needs review (${pending.size})", Modifier.weight(1f))
                    if (pending.size > 1) TextButton(onClick = vm::approveAll, enabled = !busy) { Text("Add all") }
                }
            }
            if (pending.isEmpty()) item {
                Text("Nothing to review.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pending, key = { it.id }) { c ->
                ReviewCard(c, accounts, categories, busy, onAdd = { acc, cat, merchant -> vm.approve(c, acc, cat, merchant) }, onIgnore = { vm.ignore(c) },
                    onTransfer = { nav.navigate(Routes.txnEdit(type = TxnType.TRANSFER.name)); vm.ignore(c) })
            }

            item { SectionHeader("Import past messages") }
            item {
                AppCard {
                    Text("Scan the last 30 days of bank SMS. Found payments go to the review list so nothing you already entered is counted twice.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    TonalButton(if (busy) "Scanning…" else "Scan last 30 days", onClick = {
                        if (!canReadInbox) smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                        else vm.importInbox(30)
                    }, enabled = !busy, icon = Icons.Filled.Sms)
                }
            }

            if (recent.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Recently captured", Modifier.weight(1f))
                        TextButton(onClick = vm::clearHistory) { Text("Clear") }
                    }
                }
                item {
                    AppCard(contentPadding = 0.dp) {
                        recent.forEachIndexed { i, c ->
                            HistoryRow(c) { c.transactionId?.let { nav.navigate(Routes.txnDetail(it)) } }
                            if (i < recent.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun accountLabel(a: AccountWithBalance) = a.account.name + (a.account.last4?.let { " ••$it" } ?: "")

private fun sourceLabel(c: CapturedMessageEntity) = when (c.source) {
    CaptureRepository.SOURCE_SMS -> "SMS" + (c.sender?.let { " · $it" } ?: "")
    CaptureRepository.SOURCE_FINFOX_PAY -> "Scan & Pay"
    else -> appLabel(c.source)
}

@Composable
private fun SourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String,
    granted: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, actionLabel: String, onAction: () -> Unit,
) {
    val fin = FinFoxTheme.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Icon(if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(18.dp), tint = if (granted) fin.income else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text(if (granted) "Access granted" else "Access needed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (!granted) TextButton(onClick = onAction) { Text(actionLabel) }
            else androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ReviewCard(
    c: CapturedMessageEntity,
    accounts: List<AccountWithBalance>,
    categories: List<CategoryEntity>,
    busy: Boolean,
    onAdd: (Long?, Long?, String) -> Unit,
    onIgnore: () -> Unit,
    onTransfer: () -> Unit,
) {
    val fin = FinFoxTheme.colors
    val debit = c.direction == TxnDirection.DEBIT.name
    val catType = if (debit) CategoryType.EXPENSE else CategoryType.INCOME
    val cats = categories.filter { it.type == catType }
    var accountId by rememberSaveable(c.id) { mutableStateOf(c.suggestedAccountId) }
    var categoryId by rememberSaveable(c.id) { mutableStateOf(c.suggestedCategoryId) }
    var merchant by rememberSaveable(c.id) { mutableStateOf(c.counterparty.orEmpty()) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (debit) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived, if (debit) fin.expense else fin.income)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (debit) "− " else "+ ") + Money.format(c.amountMinor), style = MaterialTheme.typography.titleMedium, color = if (debit) fin.expense else fin.income)
                Text("${Dates.formatDay(c.receivedAt)}, ${Dates.formatTime(c.receivedAt)} · ${sourceLabel(c)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (c.accountLast4 != null) Chip("••${c.accountLast4}", MaterialTheme.colorScheme.primary)
        }
        Text(c.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 8.dp))
        if (c.kind == CaptureKind.CARD_BILL_PAYMENT.name) {
            Text("This looks like a credit card bill payment. Record it as a transfer from your bank account to the card so it isn't counted as income.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Ignore", onClick = onIgnore, modifier = Modifier.weight(1f), enabled = !busy)
                PrimaryButton("Record transfer", onClick = onTransfer, modifier = Modifier.weight(1f), enabled = !busy)
            }
            return@AppCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(merchant, { merchant = it }, if (debit) "Paid to" else "Received from", leadingIcon = Icons.Filled.Storefront, maxLength = 60)
            DropdownField("Category", cats, cats.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id },
                leading = { Icon(AppIcons.forKey(it.iconKey), null, tint = Color(it.colorArgb)) })
            DropdownField("Account", accounts, accounts.firstOrNull { it.account.id == accountId }, ::accountLabel, { accountId = it.account.id },
                leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Ignore", onClick = onIgnore, modifier = Modifier.weight(1f), enabled = !busy)
                PrimaryButton("Add", onClick = { onAdd(accountId, categoryId, merchant) }, modifier = Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

@Composable
private fun HistoryRow(c: CapturedMessageEntity, onClick: () -> Unit) {
    val fin = FinFoxTheme.colors
    val debit = c.direction == TxnDirection.DEBIT.name
    val status = when {
        c.status == CaptureStatus.IGNORED -> "Ignored"
        c.transactionId == null -> "Added, since deleted"
        else -> "Added"
    }
    com.mymoney.app.ui.components.ListRow(
        if (debit) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived, if (debit) fin.expense else fin.income,
        (c.counterparty ?: if (debit) "Payment" else "Money received") + " · " + Money.format(c.amountMinor),
        "$status · ${sourceLabel(c)} · ${Dates.formatDay(c.receivedAt)}",
        onClick = if (c.transactionId != null) onClick else null,
    )
}
