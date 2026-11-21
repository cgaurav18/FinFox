package com.mymoney.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.core.PeriodFilter
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.data.repo.TxnFilter
import com.mymoney.app.data.repo.TxnInput
import com.mymoney.app.data.repo.TxnSort
import com.mymoney.app.domain.Reports
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** null period = all time. */
data class TxnListFilter(
    val type: TxnType? = null,
    val query: String = "",
    val period: PeriodFilter? = null,
    val custom: DateRange? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val sort: TxnSort = TxnSort.NEWEST,
) {
    val range: DateRange get() = period?.range(Dates.today(), custom) ?: DateRange(LocalDate.of(1970, 1, 1), LocalDate.of(2200, 1, 1))
    val activeCount: Int get() = listOfNotNull(period, accountId, categoryId, minAmount, maxAmount).size + if (sort != TxnSort.NEWEST) 1 else 0
}

data class TxnListState(
    val loading: Boolean = true,
    val groups: List<Pair<LocalDate, List<TransactionDetail>>> = emptyList(),
    val flat: List<TransactionDetail> = emptyList(),
    val count: Int = 0,
    val income: Long = 0,
    val expense: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    handle: SavedStateHandle,
    repo: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val _filter = MutableStateFlow(TxnListFilter(type = handle.get<String>("type")?.let { runCatching { TxnType.valueOf(it) }.getOrNull() }))
    val filter: StateFlow<TxnListFilter> = _filter.asStateFlow()

    val accounts = accounts.observeAccounts(true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.observe(null, true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<TxnListState> = _filter.flatMapLatest { f ->
        repo.observeFiltered(TxnFilter(f.range, f.type, f.accountId, f.categoryId, f.minAmount, f.maxAmount, f.query)).map { list ->
            val sorted = when (f.sort) {
                TxnSort.NEWEST -> list
                TxnSort.OLDEST -> list.sortedWith(compareBy({ it.txn.dateMillis }, { it.txn.id }))
                TxnSort.HIGHEST -> list.sortedByDescending { it.txn.amountMinor }
                TxnSort.LOWEST -> list.sortedBy { it.txn.amountMinor }
            }
            val s = Reports.summary(list)
            val byDate = f.sort == TxnSort.NEWEST || f.sort == TxnSort.OLDEST
            TxnListState(false, if (byDate) Reports.groupByDay(sorted) else emptyList(), if (byDate) emptyList() else sorted, list.size, s.incomeMinor, s.expenseMinor)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState())

    fun update(transform: (TxnListFilter) -> TxnListFilter) = _filter.update(transform)
    fun resetFilters() = _filter.update { TxnListFilter(type = it.type, query = it.query) }
}

enum class PayMethod(val label: String, val type: AccountType) { BANK("Bank", AccountType.BANK), CARD("Credit Card", AccountType.CREDIT_CARD), CASH("Cash", AccountType.CASH) }

data class TxnForm(
    val id: Long? = null,
    val loaded: Boolean = false,
    val type: TxnType = TxnType.EXPENSE,
    val amount: String = "",
    val categoryId: Long? = null,
    val method: PayMethod = PayMethod.BANK,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val date: LocalDate = Dates.today(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val merchant: String = "",
    val note: String = "",
    val tags: String = "",
    val recurring: Frequency? = null,
    val amountError: String? = null,
    val linkedSchedule: Boolean = false,
)

@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: TransactionRepository,
    private val capture: com.mymoney.app.autocapture.CaptureRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val editId: Long? = handle.get<Long>("id")?.takeIf { it > 0 }
    private val _form = MutableStateFlow(TxnForm(id = editId, type = handle.get<String>("type")?.let { runCatching { TxnType.valueOf(it) }.getOrNull() } ?: TxnType.EXPENSE, loaded = editId == null))
    val form: StateFlow<TxnForm> = _form.asStateFlow()

    val accounts: StateFlow<List<AccountWithBalance>> = accounts.observeAccounts(true).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<CategoryEntity>> = combine(categories.observe(null, true), _form) { all, f ->
        val want = if (f.type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        all.filter { it.type == want && (!it.archived || it.id == f.categoryId) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            if (editId != null) {
                val t = repo.get(editId)
                if (t == null) { bus.show("Transaction not found"); finish(); return@launch }
                val acc = accounts.get(t.accountId)
                val dt = Dates.toLocalDateTime(t.dateMillis)
                _form.value = TxnForm(
                    id = t.id, loaded = true, type = t.type, amount = Money.toInput(t.amountMinor), categoryId = t.categoryId,
                    method = PayMethod.entries.first { it.type == acc?.type }, accountId = t.accountId, toAccountId = t.toAccountId,
                    date = dt.toLocalDate(), time = dt.toLocalTime(), merchant = t.merchant ?: "", note = t.note ?: "",
                    tags = t.tags.split(',').filter { it.isNotBlank() }.joinToString(", "), linkedSchedule = t.scheduleId != null,
                )
            }
        }
        // New transactions: pre-select the first active account of the chosen method once accounts load.
        viewModelScope.launch { this@AddTransactionViewModel.accounts.collect { if (_form.value.accountId == null && _form.value.loaded) pickDefaultAccount(it) } }
    }

    private fun pickDefaultAccount(list: List<AccountWithBalance>) {
        val active = list.filter { !it.account.archived }
        val f = _form.value
        val byMethod = active.firstOrNull { it.account.type == f.method.type }
        val any = active.firstOrNull { f.type != TxnType.TRANSFER || it.account.type != AccountType.CREDIT_CARD }
        val pick = byMethod ?: any
        _form.update { it.copy(accountId = pick?.account?.id, method = pick?.let { p -> PayMethod.entries.first { m -> m.type == p.account.type } } ?: it.method) }
    }

    fun setType(t: TxnType) {
        _form.update {
            val method = if (t == TxnType.TRANSFER && it.method == PayMethod.CARD) PayMethod.BANK else it.method
            it.copy(type = t, categoryId = null, toAccountId = null, method = method, recurring = if (t == TxnType.INCOME) null else it.recurring,
                accountId = if (method != it.method) null else it.accountId)
        }
        if (_form.value.accountId == null) pickDefaultAccount(accounts.value)
    }

    fun setMethod(m: PayMethod) {
        _form.update { it.copy(method = m, accountId = accounts.value.firstOrNull { a -> a.account.type == m.type && !a.account.archived }?.account?.id) }
    }

    fun edit(transform: (TxnForm) -> TxnForm) = _form.update(transform)

    fun save() {
        val f = _form.value
        val amount = when (val r = Money.parse(f.amount)) {
            is Money.ParseResult.Ok -> r.minor
            is Money.ParseResult.Error -> { _form.update { it.copy(amountError = r.message) }; return }
        }
        if (f.accountId == null) { bus.show("Select an account"); return }
        if (f.type != TxnType.TRANSFER && f.categoryId == null) { bus.show("Select a category"); return }
        if (f.type == TxnType.TRANSFER && f.toAccountId == null) { bus.show("Select the destination account"); return }
        val label = when (f.type) { TxnType.INCOME -> "Income"; TxnType.TRANSFER -> "Transfer"; else -> "Expense" }
        launchSafe(success = if (f.id == null) "$label saved" else "$label updated", finishOnSuccess = true) {
            val savedId = repo.save(
                TxnInput(
                    id = f.id, type = f.type, amountMinor = amount, accountId = f.accountId, toAccountId = f.toAccountId,
                    categoryId = f.categoryId, dateMillis = Dates.toMillis(f.date, f.time), merchant = f.merchant, note = f.note,
                    tags = f.tags, recurring = if (f.id == null) f.recurring else null,
                )
            )
            // Correcting an auto-captured entry teaches FinFox the merchant's category for next time.
            if (f.id != null) capture.learnFromEdit(savedId)
        }
    }
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: TransactionRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val id: Long = handle["id"] ?: -1
    val detail = repo.observeDetail(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete() = launchSafe(success = "Transaction deleted", finishOnSuccess = true) { repo.delete(id) }
}
