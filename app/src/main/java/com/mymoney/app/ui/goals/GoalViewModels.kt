package com.mymoney.app.ui.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.GoalInput
import com.mymoney.app.data.repo.GoalRepository
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsViewModel @Inject constructor(repo: GoalRepository, bus: MessageBus) : BaseViewModel(bus) {
    val archived = MutableStateFlow(false)
    val goals = archived.flatMapLatest { repo.observeGoals(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

data class GoalForm(
    val id: Long? = null,
    val loaded: Boolean = false,
    val name: String = "",
    val target: String = "",
    val targetDate: LocalDate? = null,
    val initial: String = "",
    val linkedAccountId: Long? = null,
    val color: Long = com.mymoney.app.ui.components.AppIcons.DEFAULT_COLOR,
    val nameError: String? = null,
    val targetError: String? = null,
)

@HiltViewModel
class AddGoalViewModel @Inject constructor(handle: SavedStateHandle, private val repo: GoalRepository, accounts: AccountRepository, bus: MessageBus) : BaseViewModel(bus) {
    private val editId = handle.get<Long>("id")?.takeIf { it > 0 }
    private val _form = MutableStateFlow(GoalForm(id = editId, loaded = editId == null))
    val form: StateFlow<GoalForm> = _form.asStateFlow()
    val accounts = accounts.observeAccounts(false).map { l -> l.filter { it.account.type != AccountType.CREDIT_CARD } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (editId != null) viewModelScope.launch {
            val g = repo.get(editId) ?: run { bus.show("Goal not found"); finish(); return@launch }
            _form.value = GoalForm(g.id, true, g.name, Money.toInput(g.targetMinor), g.targetEpochDay?.let(LocalDate::ofEpochDay), "", g.linkedAccountId, g.colorArgb)
        }
    }

    fun edit(t: (GoalForm) -> GoalForm) = _form.update(t)

    fun save() {
        val f = _form.value
        if (f.name.isBlank()) { _form.update { it.copy(nameError = "Enter a goal name") }; return }
        val target = when (val r = Money.parse(f.target)) { is Money.ParseResult.Ok -> r.minor; is Money.ParseResult.Error -> { _form.update { it.copy(targetError = r.message) }; return } }
        val initial = if (f.initial.isBlank()) 0 else Money.parseOrNull(f.initial, allowZero = true) ?: 0
        launchSafe(if (f.id == null) "Goal created" else "Goal updated", finishOnSuccess = true) {
            repo.save(GoalInput(f.id, f.name, target, f.targetDate, initial, f.linkedAccountId, f.color))
        }
    }
}

@HiltViewModel
class GoalDetailViewModel @Inject constructor(handle: SavedStateHandle, private val repo: GoalRepository, bus: MessageBus) : BaseViewModel(bus) {
    val id: Long = handle["id"] ?: -1
    val goal = repo.observeGoal(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val contributions = repo.observeContributions(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private fun millis(d: LocalDate) = Dates.toMillis(d, if (d == Dates.today()) LocalTime.now() else LocalTime.NOON)
    fun contribute(a: Long, d: LocalDate, note: String) = launchSafe("Contribution added") { repo.contribute(id, a, millis(d), note) }
    fun withdraw(a: Long, d: LocalDate, note: String) = launchSafe("Withdrawal recorded") { repo.withdraw(id, a, millis(d), note) }
    fun deleteContribution(cid: Long) = launchSafe("Entry removed") { repo.deleteContribution(cid) }
    fun setArchived(a: Boolean) = launchSafe(if (a) "Goal archived" else "Goal restored") { repo.setArchived(id, a) }
    fun delete() = launchSafe("Goal deleted", finishOnSuccess = true) { repo.delete(id) }
}
