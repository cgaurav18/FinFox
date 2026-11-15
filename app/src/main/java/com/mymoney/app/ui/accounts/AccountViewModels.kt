package com.mymoney.app.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.repo.AccountInput
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(repo: AccountRepository, bus: MessageBus) : BaseViewModel(bus) {
    val tab = MutableStateFlow<AccountType?>(null)
    val showArchived = MutableStateFlow(false)
    private val all = repo.observeAccounts(true)
    val accounts = combine(all, tab, showArchived) { list, t, arch ->
        list.filter { (t == null || it.account.type == t) && (arch || !it.account.archived) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val totals = all.map { l -> AccountRepository.totals(l.filter { !it.account.archived }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val archivedCount = all.map { l -> l.count { it.account.archived } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

data class AccountForm(
    val id: Long? = null,
    val loaded: Boolean = false,
    val type: AccountType = AccountType.BANK,
    val name: String = "",
    val bankName: String = "",
    val last4: String = "",
    val bankAccountType: String = "Savings",
    val opening: String = "",
    val currency: String = "INR",
    val color: Long = com.mymoney.app.ui.components.AppIcons.DEFAULT_COLOR,
    val creditLimit: String = "",
    val statementDay: String = "",
    val dueDay: String = "",
    val description: String = "",
    val originalOpening: String = "",
    val reason: String = "",
    val errors: Map<String, String> = emptyMap(),
)

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: AccountRepository,
    settings: SettingsRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val editId = handle.get<Long>("id")?.takeIf { it > 0 }
    private val initialType = handle.get<String>("type")?.let { runCatching { AccountType.valueOf(it) }.getOrNull() } ?: AccountType.BANK
    private val _form = MutableStateFlow(AccountForm(id = editId, type = initialType, loaded = editId == null, color = defaultColor(initialType)))
    val form: StateFlow<AccountForm> = _form.asStateFlow()

    private fun defaultColor(t: AccountType) = when (t) { AccountType.BANK -> 0xFF5B0FA6; AccountType.CREDIT_CARD -> 0xFFFF7417; AccountType.CASH -> 0xFF0D9488 }

    init {
        viewModelScope.launch {
            if (editId == null) {
                val c = settings.get().currency
                _form.update { it.copy(currency = c) }
                return@launch
            }
            val a = repo.get(editId) ?: run { bus.show("Account not found"); finish(); return@launch }
            val opening = if (a.type == AccountType.CREDIT_CARD) -a.openingBalanceMinor else a.openingBalanceMinor
            _form.value = AccountForm(
                id = a.id, loaded = true, type = a.type, name = a.name, bankName = a.bankName ?: "", last4 = a.last4 ?: "",
                bankAccountType = a.bankAccountType ?: "Savings", opening = Money.toInput(opening), currency = a.currency, color = a.colorArgb,
                creditLimit = a.creditLimitMinor?.let(Money::toInput) ?: "", statementDay = a.statementDay?.toString() ?: "",
                dueDay = a.dueDay?.toString() ?: "", description = a.description ?: "", originalOpening = Money.toInput(opening),
            )
        }
    }

    fun setType(t: AccountType) = _form.update { if (it.id != null) it else it.copy(type = t, color = defaultColor(t), errors = emptyMap()) }
    fun edit(transform: (AccountForm) -> AccountForm) = _form.update { transform(it).copy(errors = emptyMap()) }

    fun save() {
        val f = _form.value
        val errors = mutableMapOf<String, String>()
        val opening = if (f.opening.isBlank()) 0L else Money.parseOrNull(f.opening, allowZero = true) ?: run { errors["opening"] = "Enter a valid amount"; 0L }
        val limit = if (f.type == AccountType.CREDIT_CARD) Money.parseOrNull(f.creditLimit) ?: run { errors["limit"] = "Enter the credit limit"; null } else null
        val sDay = f.statementDay.toIntOrNull()?.also { if (it !in 1..31) errors["statement"] = "1-31" }
        val dDay = f.dueDay.toIntOrNull()?.also { if (it !in 1..31) errors["due"] = "1-31" }
        if (f.name.isBlank()) errors["name"] = "Enter a name"
        if (f.type != AccountType.CASH && f.bankName.isBlank()) errors["bank"] = "Enter the bank name"
        if (f.last4.isNotEmpty() && f.last4.length != 4) errors["last4"] = "Enter exactly 4 digits"
        if (errors.isNotEmpty()) { _form.update { it.copy(errors = errors) }; return }
        launchSafe(success = if (f.id == null) "Account added" else "Account updated", finishOnSuccess = true) {
            repo.save(
                AccountInput(
                    id = f.id, type = f.type, name = f.name, bankName = f.bankName.takeIf { f.type != AccountType.CASH }, last4 = f.last4,
                    bankAccountType = f.bankAccountType.takeIf { f.type == AccountType.BANK }, openingMinor = opening, currency = f.currency,
                    colorArgb = f.color, iconKey = when (f.type) { AccountType.BANK -> "bank"; AccountType.CREDIT_CARD -> "credit_card"; AccountType.CASH -> "cash" },
                    creditLimitMinor = limit, statementDay = sDay, dueDay = dDay, description = f.description, adjustmentReason = f.reason,
                )
            )
        }
    }
}

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: AccountRepository,
    txns: TransactionRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val id: Long = handle["id"] ?: -1
    val account = repo.observeAccount(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = txns.observeForAccount(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val adjustments = repo.observeAdjustments(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setArchived(archived: Boolean) = launchSafe(if (archived) "Account archived" else "Account restored") { repo.setArchived(id, archived) }
    fun delete() = launchSafe("Account deleted", finishOnSuccess = true) { repo.delete(id) }
    /** For credit cards [target] is the outstanding amount. */
    fun reconcile(target: Long, isCard: Boolean, reason: String) =
        launchSafe("Balance updated") { repo.reconcileBalance(id, if (isCard) -target else target, reason) }
}
