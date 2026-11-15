package com.mymoney.app.ui.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.ScheduleDetail
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.ScheduleInput
import com.mymoney.app.data.repo.ScheduleRepository
import com.mymoney.app.domain.ScheduleMath
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class ScheduleView(val label: String) { UPCOMING("Upcoming"), OVERDUE("Overdue"), ALL("All"), CALENDAR("Calendar") }
enum class ScheduleGroup(val label: String, val cats: Set<ScheduleCategory>?) {
    ALL("All", null), EMI("EMI", setOf(ScheduleCategory.EMI)), SIP("SIP", setOf(ScheduleCategory.SIP)),
    BILL("Bills", setOf(ScheduleCategory.ELECTRICITY, ScheduleCategory.INTERNET, ScheduleCategory.MOBILE, ScheduleCategory.CREDIT_CARD_BILL, ScheduleCategory.INSURANCE, ScheduleCategory.RENT)),
    OTHER("Other", setOf(ScheduleCategory.SUBSCRIPTION, ScheduleCategory.CUSTOM)),
}

data class ScheduleListState(
    val loading: Boolean = true,
    val items: List<ScheduleDetail> = emptyList(),
    val all: List<ScheduleDetail> = emptyList(),
    val overdueCount: Int = 0,
    val dueThisMonthMinor: Long = 0,
    val today: LocalDate = Dates.today(),
)

@HiltViewModel
class SchedulesViewModel @Inject constructor(private val repo: ScheduleRepository, accounts: AccountRepository, bus: MessageBus) : BaseViewModel(bus) {
    val view = MutableStateFlow(ScheduleView.UPCOMING)
    val group = MutableStateFlow(ScheduleGroup.ALL)
    val calendarMonth = MutableStateFlow(YearMonth.now())
    val accounts = accounts.observeAccounts(false).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<ScheduleListState> = combine(repo.observeAll(), view, group) { list, v, g ->
        val today = Dates.today()
        val grouped = list.filter { g.cats == null || it.schedule.category in g.cats }
        fun isOverdue(d: ScheduleDetail) = d.schedule.status == ScheduleStatus.ACTIVE && d.schedule.nextDueEpochDay < today.toEpochDay()
        val items = when (v) {
            ScheduleView.UPCOMING -> grouped.filter { it.schedule.status == ScheduleStatus.ACTIVE && !isOverdue(it) }
            ScheduleView.OVERDUE -> grouped.filter(::isOverdue)
            ScheduleView.ALL, ScheduleView.CALENDAR -> grouped
        }
        val month = YearMonth.from(today)
        val dueThisMonth = list.filter { it.schedule.status == ScheduleStatus.ACTIVE }.sumOf { d ->
            val s = d.schedule
            ScheduleMath.occurrencesBetween(LocalDate.ofEpochDay(s.startEpochDay), s.frequency, LocalDate.ofEpochDay(s.nextDueEpochDay),
                s.endEpochDay?.let(LocalDate::ofEpochDay), month.atDay(1), month.atEndOfMonth()).size * s.amountMinor
        }
        ScheduleListState(false, items, grouped, list.count(::isOverdue), dueThisMonth, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleListState())

    fun markPaid(id: Long, amount: Long, accountId: Long, date: LocalDate) = launchSafe("Marked as paid") { repo.markPaid(id, null, amount, accountId, date) }
}

data class ScheduleForm(
    val id: Long? = null,
    val loaded: Boolean = false,
    val category: ScheduleCategory = ScheduleCategory.EMI,
    val title: String = "",
    val amount: String = "",
    val accountId: Long? = null,
    val isTransfer: Boolean = false,
    val toAccountId: Long? = null,
    val expenseCategoryId: Long? = null,
    val frequency: Frequency = Frequency.MONTHLY,
    val start: LocalDate = Dates.today(),
    val end: LocalDate? = null,
    val nextDue: LocalDate? = null,
    val nextDueTouched: Boolean = false,
    val reminderDays: Int = 1,
    val notes: String = "",
    val active: Boolean = true,
    val amountError: String? = null,
    val titleError: String? = null,
) {
    val computedNextDue: LocalDate get() = nextDue ?: ScheduleMath.firstOnOrAfter(start, frequency, maxOf(start, Dates.today()))
}

@HiltViewModel
class AddScheduleViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ScheduleRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val editId = handle.get<Long>("id")?.takeIf { it > 0 }
    private val _form = MutableStateFlow(ScheduleForm(id = editId, loaded = editId == null))
    val form: StateFlow<ScheduleForm> = _form.asStateFlow()
    val accounts = accounts.observeAccounts(true).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories = categories.observe(CategoryType.EXPENSE).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            if (editId != null) {
                val s = repo.get(editId) ?: run { bus.show("Schedule not found"); finish(); return@launch }
                _form.value = ScheduleForm(
                    id = s.id, loaded = true, category = s.category, title = s.title, amount = Money.toInput(s.amountMinor), accountId = s.accountId,
                    isTransfer = s.toAccountId != null, toAccountId = s.toAccountId, expenseCategoryId = s.expenseCategoryId, frequency = s.frequency,
                    start = LocalDate.ofEpochDay(s.startEpochDay), end = s.endEpochDay?.let(LocalDate::ofEpochDay), nextDue = LocalDate.ofEpochDay(s.nextDueEpochDay),
                    nextDueTouched = true, reminderDays = s.reminderDaysBefore, notes = s.notes ?: "", active = s.status != ScheduleStatus.PAUSED,
                )
            }
        }
        viewModelScope.launch {
            this@AddScheduleViewModel.accounts.collect { list ->
                if (_form.value.accountId == null && _form.value.loaded) _form.update { f -> f.copy(accountId = list.firstOrNull { !it.account.archived && it.account.type != AccountType.CREDIT_CARD }?.account?.id) }
            }
        }
    }

    fun edit(transform: (ScheduleForm) -> ScheduleForm) = _form.update {
        val n = transform(it)
        // Keep the next-due date in sync with start/frequency until the user picks one explicitly.
        if (!n.nextDueTouched) n.copy(nextDue = null) else n
    }

    fun setCategory(c: ScheduleCategory) = edit {
        it.copy(category = c, isTransfer = c == ScheduleCategory.CREDIT_CARD_BILL,
            toAccountId = null, title = if (it.title.isBlank() || ScheduleCategory.entries.any { e -> e.label == it.title }) c.label else it.title)
    }

    fun save() {
        val f = _form.value
        val amount = when (val r = Money.parse(f.amount)) { is Money.ParseResult.Ok -> r.minor; is Money.ParseResult.Error -> { _form.update { it.copy(amountError = r.message) }; return } }
        if (f.title.isBlank()) { _form.update { it.copy(titleError = "Enter a title") }; return }
        launchSafe(if (f.id == null) "Schedule added" else "Schedule updated", finishOnSuccess = true) {
            repo.save(
                ScheduleInput(
                    id = f.id, title = f.title, category = f.category, amountMinor = amount, accountId = f.accountId,
                    toAccountId = if (f.isTransfer) f.toAccountId ?: throw com.mymoney.app.core.FinanceException(if (f.category == ScheduleCategory.CREDIT_CARD_BILL) "Choose the credit card this bill pays" else "Choose the destination account") else null,
                    expenseCategoryId = if (f.isTransfer) null else f.expenseCategoryId, start = f.start, end = f.end, frequency = f.frequency,
                    nextDue = if (f.nextDueTouched) f.nextDue else null, reminderDaysBefore = f.reminderDays, notes = f.notes, active = f.active,
                )
            )
        }
    }
}

@HiltViewModel
class ScheduleDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ScheduleRepository,
    accounts: AccountRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val id: Long = handle["id"] ?: -1
    val schedule = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = repo.observeOccurrences(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts = accounts.observeAccounts(false).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markPaid(amount: Long, accountId: Long, date: LocalDate) = launchSafe("Payment recorded") { repo.markPaid(id, null, amount, accountId, date) }
    fun skip() = launchSafe("Installment skipped") { repo.skip(id) }
    fun setPaused(p: Boolean) = launchSafe(if (p) "Schedule paused" else "Schedule resumed") { repo.setPaused(id, p) }
    fun delete() = launchSafe("Schedule deleted", finishOnSuccess = true) { repo.delete(id) }
}
