package com.mymoney.app.ui.pay

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.ui.components.AmountField
import com.mymoney.app.ui.components.AppCard
import com.mymoney.app.ui.components.AppIcons
import com.mymoney.app.ui.components.AppScaffold
import com.mymoney.app.ui.components.AppTextField
import com.mymoney.app.ui.components.Chip
import com.mymoney.app.ui.components.DropdownField
import com.mymoney.app.ui.components.EmptyState
import com.mymoney.app.ui.components.IconBadge
import com.mymoney.app.ui.components.PrimaryButton
import com.mymoney.app.ui.components.TonalButton
import com.mymoney.app.ui.theme.FinFoxTheme
import kotlinx.coroutines.launch

@Composable
fun PayScreen(nav: NavController, vm: PayViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.done.collect { nav.popBackStack() } }

    val payLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val extras = data?.extras?.keySet()?.associateWith { k -> runCatching { data.extras?.get(k)?.toString() }.getOrNull() }.orEmpty()
        val response = data?.getStringExtra("response")
        // Some apps return RESULT_CANCELED with a valid response; trust the response when there is one.
        if (res.resultCode != Activity.RESULT_OK && response == null && extras.isEmpty()) vm.onResult(null, emptyMap())
        else vm.onResult(response, extras)
    }

    fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { vm.onScanned(it.rawValue) }
            .addOnCanceledListener { vm.scanCancelled() }
            .addOnFailureListener { vm.scanCancelled() }
    }
    LaunchedEffect(Unit) { if (f.stage == PayStage.SCANNING) startScan() }
    LifecycleResumeEffect(Unit) { vm.onResumed(); onPauseOrDispose { } }

    val clipboard = LocalClipboardManager.current
    fun startPay(l: PayLaunch) {
        if (l.handoff) {
            f.upi?.let { clipboard.setText(AnnotatedString(it.pa)) }
            try { context.startActivity(l.intent) } catch (_: ActivityNotFoundException) { vm.onAppLaunchFailed() }
        } else {
            try { payLauncher.launch(l.intent) } catch (_: ActivityNotFoundException) { vm.onAppLaunchFailed() }
        }
    }

    f.handoff?.let { h ->
        val payee = f.upi?.displayName ?: ""
        AlertDialog(
            onDismissRequest = vm::cancelHandoff,
            title = { Text(if (h.afterFailure) "Pay inside ${h.app.label} instead" else "Pay ${Money.format(h.amountMinor)} in ${h.app.label}") },
            text = {
                Text(
                    (if (h.afterFailure) "${h.app.label} didn't allow FinFox to start this payment. "
                    else "UPI apps don't let other apps start payments to people, so you'll pay inside ${h.app.label}. ") +
                        "FinFox copies the UPI ID ${f.upi?.pa ?: ""}: in ${h.app.label}, choose Pay UPI ID, paste it and pay " +
                        "${Money.format(h.amountMinor)} to $payee.\n\nFinFox records it under the category you chose when the bank SMS " +
                        "or payment notification arrives, or when you confirm on your return.\n\nChange how this works in Settings → Scan & Pay.",
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { vm.confirmHandoff()?.let(::startPay) } }) { Text("Copy UPI ID & open ${h.app.label}") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::cancelHandoff) { Text("Cancel") }
                    // A shop QR FinFox didn't recognise: let the user try the direct redirect.
                    if (!h.afterFailure) TextButton(onClick = { scope.launch { vm.payDirect()?.let(::startPay) } }) { Text("It's a shop: open directly") }
                }
            },
        )
    }

    if (f.askOutcome) AlertDialog(
        onDismissRequest = { vm.answerOutcome(null) },
        title = { Text("Did the payment go through?") },
        text = { Text("FinFox couldn't get the result from the UPI app. If you're not sure, FinFox records it automatically when your bank's SMS arrives (with auto-capture on).") },
        confirmButton = { TextButton(onClick = { vm.answerOutcome(true) }) { Text("Yes, record it") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.answerOutcome(null) }) { Text("Not sure") }
                TextButton(onClick = { vm.answerOutcome(false) }) { Text("No") }
            }
        },
    )

    AppScaffold(title = "Scan & Pay", onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (f.stage) {
                PayStage.SCANNING -> {
                    EmptyState(Icons.Filled.QrCodeScanner, "Point your camera at a UPI QR code", "Any UPI QR works: shops, people, bills.", action = "Scan again") { startScan() }
                }
                PayStage.ENTER_PAYEE -> {
                    TonalButton("Scan a QR code", onClick = ::startScan, icon = Icons.Filled.QrCodeScanner, modifier = Modifier.padding(bottom = 4.dp))
                    Text("Or pay a UPI ID", style = MaterialTheme.typography.titleSmall)
                    AppTextField(f.vpaInput, { v -> vm.edit { it.copy(vpaInput = v.trim(), vpaError = null) } }, "UPI ID", leadingIcon = Icons.Filled.AlternateEmail,
                        placeholder = "name@okbank", error = f.vpaError, keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, maxLength = 100)
                    AppTextField(f.nameInput, { v -> vm.edit { it.copy(nameInput = v) } }, "Name (optional)", leadingIcon = Icons.Filled.Person,
                        capitalization = KeyboardCapitalization.Words, maxLength = 60)
                    PrimaryButton("Continue", onClick = vm::submitPayee)
                }
                PayStage.FORM -> PayForm(f, vm, accounts, categories, busy) { app ->
                    scope.launch {
                        vm.prepare(app)?.let(::startPay)
                    }
                }
            }
        }
    }
}

@Composable
private fun PayForm(
    f: PayForm,
    vm: PayViewModel,
    accounts: List<com.mymoney.app.data.db.AccountWithBalance>,
    categories: List<com.mymoney.app.data.db.CategoryEntity>,
    busy: Boolean,
    onPay: (UpiApp) -> Unit,
) {
    val upi = f.upi ?: return
    val fin = FinFoxTheme.colors
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (upi.isMerchant) Icons.Filled.Storefront else Icons.Filled.Person, MaterialTheme.colorScheme.primary, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Paying", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(upi.displayName, style = MaterialTheme.typography.titleMedium)
                Text(upi.pa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (upi.isMerchant) Chip("Merchant", fin.income)
        }
    }
    if (upi.amountMinor != null) {
        AppCard {
            Text("Amount set by the QR code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(upi.amountMinor), style = MaterialTheme.typography.headlineSmall)
        }
    } else {
        AmountField(f.amount, { v -> vm.edit { it.copy(amount = v, amountError = null) } }, error = f.amountError)
    }
    CategoryPicker(f, categories, upi.displayName, vm::selectCategory)
    DropdownField(
        "Pay from", accounts, accounts.firstOrNull { it.account.id == f.accountId },
        { it.account.name + (it.account.last4?.let { l -> " ••$l" } ?: "") + if (it.account.type == AccountType.CREDIT_CARD) " (credit card)" else "" },
        { a -> vm.edit { it.copy(accountId = a.account.id) } },
        leading = { Icon(AppIcons.forAccount(it.account.type), null, tint = Color(it.account.colorArgb)) },
        placeholder = "Add a bank account first",
    )
    AppTextField(f.note, { v -> vm.edit { it.copy(note = v) } }, "Note (optional)", leadingIcon = Icons.AutoMirrored.Filled.Notes, maxLength = 50)

    Spacer(Modifier.height(4.dp))
    Text("Pay with", style = MaterialTheme.typography.titleSmall)
    if (vm.apps.isEmpty()) {
        Text("No UPI app is installed. Install Google Pay, PhonePe, Paytm, BHIM or your bank's app to pay.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    } else AppCard(contentPadding = 0.dp) {
        vm.apps.forEachIndexed { i, app ->
            val bmp = remember(app.packageName) { app.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() } }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { onPay(app) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bmp != null) Image(bmp, null, Modifier.size(42.dp))
                else IconBadge(Icons.Filled.QrCodeScanner, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(app.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Pay with ${app.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (i < vm.apps.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            "You enter your UPI PIN in the UPI app; FinFox never sees it. The expense is recorded when the app confirms the payment, or when your bank's SMS arrives.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "What is this payment for?": the expense category is chosen here, before paying, and the payment is recorded
 * under it whether the UPI app, the bank SMS, an email or a UPI notification confirms it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(f: PayForm, categories: List<com.mymoney.app.data.db.CategoryEntity>, payee: String, onSelect: (Long) -> Unit) {
    AppCard(contentPadding = 12.dp) {
        Text("What is this payment for?", style = MaterialTheme.typography.titleSmall)
        val hint = when {
            f.categoryMissing -> "Choose a category to continue"
            f.categorySuggested -> "Suggested from your earlier payments to $payee. Tap another to change it."
            else -> "FinFox records the expense under this category automatically."
        }
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = if (f.categoryMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.forEach { c ->
                FilterChip(
                    selected = f.categoryId == c.id,
                    onClick = { onSelect(c.id) },
                    label = { Text(c.name) },
                    leadingIcon = { Icon(AppIcons.forKey(c.iconKey), null, Modifier.size(18.dp), tint = Color(c.colorArgb)) },
                )
            }
        }
    }
}
