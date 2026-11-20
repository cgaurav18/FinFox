package com.mymoney.app.ui.pay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.autocapture.CaptureRepository
import com.mymoney.app.autocapture.UpiResponse
import com.mymoney.app.autocapture.UpiResultStatus
import com.mymoney.app.autocapture.UpiUri
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class UpiApp(val packageName: String, val label: String, val icon: Drawable?)

enum class PayStage { SCANNING, ENTER_PAYEE, FORM }

data class PayForm(
    val stage: PayStage = PayStage.SCANNING,
    val upi: UpiUri? = null,
    val vpaInput: String = "",
    val nameInput: String = "",
    val vpaError: String? = null,
    val amount: String = "",
    val amountError: String? = null,
    val categoryId: Long? = null,
    /** The category was pre-selected from what this payee was categorised as before. */
    val categorySuggested: Boolean = false,
    /** The user tried to pay without choosing a category. */
    val categoryMissing: Boolean = false,
    val accountId: Long? = null,
    val note: String = "",
    /** The UPI app didn't report a clear result: ask the user. */
    val askOutcome: Boolean = false,
)

@HiltViewModel
class PayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: SavedStateHandle,
    private val capture: CaptureRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val scanFirst: Boolean = handle.get<Boolean>("scan") ?: true
    private val _form = MutableStateFlow(PayForm(stage = if (scanFirst) PayStage.SCANNING else PayStage.ENTER_PAYEE))
    val form: StateFlow<PayForm> = _form.asStateFlow()

    /** UPI works from bank accounts and RuPay credit cards. */
    val accounts: StateFlow<List<AccountWithBalance>> = accounts.observeAccounts(false)
        .map { list -> list.filter { it.account.type != AccountType.CASH } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categories.observe(CategoryType.EXPENSE)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val apps: List<UpiApp> = queryUpiApps()

    /** Survives process death while the user is in the UPI app. */
    private var paymentId: Long?
        get() = handle.get<Long>("paymentId")
        set(v) { handle["paymentId"] = v }

    init {
        viewModelScope.launch {
            val prefs = capture.prefs()
            this@PayViewModel.accounts.collect { list ->
                if (_form.value.accountId == null && list.isNotEmpty()) {
                    val pick = prefs.defaultAccountId?.takeIf { id -> list.any { it.account.id == id } }
                        ?: list.firstOrNull { it.account.type == AccountType.BANK }?.account?.id ?: list.first().account.id
                    _form.update { it.copy(accountId = pick) }
                }
            }
        }
    }

    fun edit(transform: (PayForm) -> PayForm) = _form.update(transform)

    fun selectCategory(id: Long) = _form.update { it.copy(categoryId = id, categorySuggested = false, categoryMissing = false) }

    fun scanCancelled() { if (_form.value.upi == null) _form.update { it.copy(stage = PayStage.ENTER_PAYEE) } }

    /** Returns false when the code isn't a UPI QR. */
    fun onScanned(payload: String?): Boolean {
        val upi = payload?.let(UpiUri::parse)
        if (upi == null) {
            bus.show("That QR code isn't a UPI payment code")
            _form.update { it.copy(stage = PayStage.ENTER_PAYEE) }
            return false
        }
        useUpi(upi)
        return true
    }

    fun submitPayee() {
        val f = _form.value
        val vpa = f.vpaInput.trim()
        if (!UpiUri.isValidVpa(vpa)) { _form.update { it.copy(vpaError = "Enter a UPI ID like name@okbank") }; return }
        useUpi(UpiUri.manual(vpa, f.nameInput))
    }

    private fun useUpi(upi: UpiUri) {
        _form.update {
            it.copy(stage = PayStage.FORM, upi = upi, amount = upi.amountMinor?.let(Money::toInput) ?: it.amount, note = upi.tn ?: it.note)
        }
        viewModelScope.launch {
            val cat = capture.suggestCategory(upi.pa, upi.pn)
            _form.update { f -> if (f.categoryId == null && cat != null) f.copy(categoryId = cat, categorySuggested = true) else f }
        }
    }

    /** Validates the form, records the pending payment and returns the intent to launch, or null. */
    suspend fun prepare(app: UpiApp): Intent? {
        val f = _form.value
        val upi = f.upi ?: return null
        val amount = upi.amountMinor ?: when (val r = Money.parse(f.amount)) {
            is Money.ParseResult.Ok -> r.minor
            is Money.ParseResult.Error -> { _form.update { it.copy(amountError = r.message) }; return null }
        }
        if (amount > 100_000_00L * 5) { _form.update { it.copy(amountError = "UPI payments are limited to ₹5,00,000") }; return null }
        if (f.categoryId == null) { _form.update { it.copy(categoryMissing = true) }; bus.show("Choose what this payment is for"); return null }
        if (f.accountId == null) { bus.show("Choose the account you're paying from"); return null }
        val ref = "FF" + UUID.randomUUID().toString().replace("-", "").take(18).uppercase()
        paymentId = capture.startPayment(upi.pa, upi.pn, amount, f.categoryId, f.accountId, f.note.trim().ifBlank { null }, ref, app.packageName)
        return Intent(Intent.ACTION_VIEW, Uri.parse(upi.toPayUri(amount, f.note, ref))).setPackage(app.packageName)
    }

    fun onAppLaunchFailed() {
        paymentId?.let { id -> viewModelScope.launch { capture.failPayment(id) } }
        bus.show("Couldn't open that UPI app")
    }

    fun onResult(response: String?, extras: Map<String, String?>) {
        val id = paymentId ?: return
        val r = UpiResponse.parse(response, extras)
        when (r.status) {
            UpiResultStatus.SUCCESS -> record(id, r.approvalRef)
            UpiResultStatus.FAILURE -> viewModelScope.launch {
                capture.failPayment(id)
                bus.show("The payment failed or was cancelled. Nothing was recorded.")
            }
            UpiResultStatus.SUBMITTED, UpiResultStatus.UNKNOWN -> _form.update { it.copy(askOutcome = true) }
        }
    }

    /** Answer to "Did the payment go through?". null = not sure: wait for the bank SMS. */
    fun answerOutcome(paid: Boolean?) {
        val id = paymentId ?: return
        _form.update { it.copy(askOutcome = false) }
        when (paid) {
            true -> record(id, null)
            false -> viewModelScope.launch { capture.failPayment(id); bus.show("Okay, nothing was recorded"); finish() }
            null -> { bus.show("FinFox will record it when your bank's SMS arrives"); finish() }
        }
    }

    private fun record(id: Long, approvalRef: String?) {
        launchSafe(success = "Payment recorded", finishOnSuccess = true) { capture.completePayment(id, approvalRef) }
    }

    private fun queryUpiApps(): List<UpiApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
        val infos = if (android.os.Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        return infos.map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }.map { pkg ->
            val info = infos.first { it.activityInfo.packageName == pkg }
            UpiApp(pkg, info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm) }.getOrNull())
        }.sortedBy { it.label.lowercase() }
    }
}
