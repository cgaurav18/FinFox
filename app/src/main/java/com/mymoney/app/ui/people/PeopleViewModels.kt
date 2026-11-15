package com.mymoney.app.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.LoanInput
import com.mymoney.app.data.repo.LoanRepository
import com.mymoney.app.data.repo.SettleMode
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

enum class PeopleTab(val label: String) { GAVE("I Gave"), RECEIVED("I Received"), PEOPLE("People") }

data class PeopleTotals(val lent: Long = 0, val borrowed: Long = 0, val lentOverdue: Long = 0, val borrowedOverdue: Long = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PeopleViewModel @Inject constructor(repo: LoanRepository, bus: MessageBus) : BaseViewModel(bus) {
    val tab = MutableStateFlow(PeopleTab.GAVE)
    val query = MutableStateFlow("")

    val loans = combine(tab, query) { t, q -> t to q }.flatMapLatest { (t, q) ->
        val dir = if (t == PeopleTab.RECEIVED) LoanDirection.RECEIVED else LoanDirection.GAVE
        repo.observeLoans(dir).map { list -> list.filter { q.isBlank() || it.personName.contains(q.trim(), ignoreCase = true) || (it.loan.description ?: "").contains(q.trim(), true) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val people = combine(repo.observePersonSummaries(), query) { list, q -> list.filter { q.isBlank() || it.name.contains(q.trim(), true) || (it.phone ?: "").contains(q.trim()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totals = combine(
        repo.observeOutstanding(LoanDirection.GAVE), repo.observeOutstanding(LoanDirection.RECEIVED),
        repo.observeOverdue(LoanDirection.GAVE), repo.observeOverdue(LoanDirection.RECEIVED),
    ) { a, b, c, d -> PeopleTotals(a, b, c, d) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleTotals())
}

data class LoanForm(
    val id: Long? = null,
    val loaded: Boolean = false,
    val direction: LoanDirection = LoanDirection.GAVE,
    val person: String = "",
    val phone: String = "",
    val amount: String = "",
    val date: LocalDate = Dates.today(),
    val due: LocalDate? = null,
    val accountId: Long? = null,
    val description: String = "",
    val reminder: Boolean = true,
    val amountError: String? = null,
    val personError: String? = null,
    val hasRepayments: Boolean = false,
)

@HiltViewModel
class AddLoanViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: LoanRepository,
    accounts: AccountRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val editId = handle.get<Long>("id")?.takeIf { it > 0 }
    private val _form = MutableStateFlow(
        LoanForm(
            id = editId, loaded = editId == null,
            direction = handle.get<String>("direction")?.let { runCatching { LoanDirection.valueOf(it) }.getOrNull() } ?: LoanDirection.GAVE,
            person = handle.get<String>("person") ?: "",
        )
    )
    val form: StateFlow<LoanForm> = _form.asStateFlow()
    val accounts = accounts.observeAccounts(true).map { l -> l.filter { it.account.type != AccountType.CREDIT_CARD } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val people = repo.observePeople().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            if (editId != null) {
                val l = repo.getLoan(editId) ?: run { bus.show("Entry not found"); finish(); return@launch }
                _form.value = LoanForm(
                    id = l.loan.id, loaded = true, direction = l.loan.direction, person = l.personName, phone = l.personPhone ?: "",
                    amount = Money.toInput(l.loan.amountMinor), date = Dates.toLocalDate(l.loan.dateMillis), due = l.loan.dueEpochDay?.let(LocalDate::ofEpochDay),
                    accountId = l.loan.accountId, description = l.loan.description ?: "", reminder = l.loan.reminderEnabled, hasRepayments = l.repaidMinor > 0,
                )
            } else {
                val p = _form.value.person
                if (p.isNotBlank()) people.value.firstOrNull { it.name.equals(p, true) }?.phone?.let { ph -> _form.update { it.copy(phone = ph) } }
            }
        }
        viewModelScope.launch {
            this@AddLoanViewModel.accounts.collect { list -> if (_form.value.accountId == null && _form.value.loaded) _form.update { f -> f.copy(accountId = list.firstOrNull { !it.account.archived }?.account?.id) } }
        }
    }

    fun edit(transform: (LoanForm) -> LoanForm) = _form.update(transform)

    fun pickPerson(name: String, phone: String?) = _form.update { it.copy(person = name, phone = phone ?: it.phone, personError = null) }

    fun save() {
        val f = _form.value
        val amount = when (val r = Money.parse(f.amount)) { is Money.ParseResult.Ok -> r.minor; is Money.ParseResult.Error -> { _form.update { it.copy(amountError = r.message) }; return } }
        if (f.person.isBlank()) { _form.update { it.copy(personError = "Enter the person's name") }; return }
        launchSafe(if (f.id == null) "Entry saved" else "Entry updated", finishOnSuccess = true) {
            repo.saveLoan(
                LoanInput(
                    id = f.id, personName = f.person, phone = f.phone, direction = f.direction, amountMinor = amount,
                    dateMillis = Dates.toMillis(f.date, if (f.date == Dates.today()) LocalTime.now() else LocalTime.NOON),
                    due = f.due, accountId = f.accountId, description = f.description, reminder = f.reminder,
                )
            )
        }
    }
}

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: LoanRepository,
    accounts: AccountRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val id: Long = handle["id"] ?: -1
    val loan = repo.observeLoan(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val repayments = repo.observeRepayments(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts = accounts.observeAccounts(false).map { l -> l.filter { it.account.type != AccountType.CREDIT_CARD } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun millis(d: LocalDate) = Dates.toMillis(d, if (d == Dates.today()) LocalTime.now() else LocalTime.NOON)

    fun addRepayment(amount: Long, date: LocalDate, accountId: Long?, note: String) = launchSafe("Repayment recorded") { repo.addRepayment(id, amount, millis(date), accountId, note) }
    fun updateRepayment(rid: Long, amount: Long, date: LocalDate, accountId: Long?, note: String) = launchSafe("Repayment updated") { repo.updateRepayment(rid, amount, millis(date), accountId, note) }
    fun deleteRepayment(rid: Long) = launchSafe("Repayment deleted") { repo.deleteRepayment(rid) }
    fun settle(mode: SettleMode) = launchSafe("Marked as settled") { repo.settle(id, mode) }
    fun reopen() = launchSafe("Entry reopened") { repo.reopen(id) }
    fun delete() = launchSafe("Entry deleted", finishOnSuccess = true) { repo.deleteLoan(id) }
}

@HiltViewModel
class PersonViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: LoanRepository,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val id: Long = handle["id"] ?: -1
    val person = repo.observePerson(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val loans = repo.observeLoansForPerson(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun update(name: String, phone: String) = launchSafe("Contact updated") { repo.updatePerson(id, name, phone) }
    fun delete() = launchSafe("Person removed", finishOnSuccess = true) { repo.deletePerson(id) }
}
