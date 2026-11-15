package com.mymoney.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.ScheduleDetail
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.BalanceTotals
import com.mymoney.app.data.repo.LoanRepository
import com.mymoney.app.data.repo.ScheduleRepository
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.domain.Reports
import com.mymoney.app.domain.SeriesPoint
import com.mymoney.app.domain.Slice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class LoanTotals(val lent: Long = 0, val borrowed: Long = 0, val lentOverdue: Long = 0, val borrowedOverdue: Long = 0)

data class MonthData(val income: Long = 0, val expense: Long = 0, val slices: List<Slice> = emptyList(), val sixMonths: List<SeriesPoint> = emptyList()) {
    val net get() = income - expense
}

data class HomeState(
    val loading: Boolean = true,
    val name: String = "",
    val totals: BalanceTotals = BalanceTotals(0, 0, 0, 0),
    val accountCount: Int = 0,
    val month: YearMonth = YearMonth.now(),
    val monthData: MonthData = MonthData(),
    val loans: LoanTotals = LoanTotals(),
    val upcoming: List<ScheduleDetail> = emptyList(),
    val recent: List<TransactionDetail> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountRepository,
    txns: TransactionRepository,
    loans: LoanRepository,
    schedules: ScheduleRepository,
    private val settings: SettingsRepository,
    profiles: com.mymoney.app.data.repo.ProfileRepository,
) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    /** Persisted "Hide balances" preference (Settings → Security), toggled by the eye on the balance card. */
    val balanceHidden: StateFlow<Boolean> = settings.settings.map { it.hideBalances }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun toggleHidden() { viewModelScope.launch { settings.update { it.copy(hideBalances = !it.hideBalances) } } }
    val profile = profiles.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.mymoney.app.data.db.UserProfileEntity())

    private val monthData = month.flatMapLatest { ym ->
        val range = DateRange(ym.minusMonths(5).atDay(1), ym.atEndOfMonth())
        txns.observeInRange(range).map { list ->
            val cur = list.filter { DateRange.month(ym).contains(it.txn.dateMillis) }
            val s = Reports.summary(cur)
            val slices = Reports.categoryBreakdown(cur).let { all ->
                if (all.size <= 5) all else all.take(4) + Slice(-2, "Others", 0xFF94A3B8, all.drop(4).sumOf { it.valueMinor }, all.drop(4).sumOf { it.percent })
            }
            MonthData(s.incomeMinor, s.expenseMinor, slices, Reports.monthly(list, ym.minusMonths(5), ym))
        }
    }

    private val loanTotals = combine(
        loans.observeOutstanding(LoanDirection.GAVE), loans.observeOutstanding(LoanDirection.RECEIVED),
        loans.observeOverdue(LoanDirection.GAVE), loans.observeOverdue(LoanDirection.RECEIVED),
    ) { a, b, c, d -> LoanTotals(a, b, c, d) }

    private val upcoming = schedules.observeAll().map { list ->
        list.filter { it.schedule.status == ScheduleStatus.ACTIVE }.sortedBy { it.schedule.nextDueEpochDay }.take(4)
    }

    val state: StateFlow<HomeState> = combine(
        combine(accounts.observeAccounts(false), settings.settings) { a, s -> Triple(AccountRepository.totals(a), a.size, s.profileName) },
        combine(month, monthData) { m, d -> m to d },
        loanTotals,
        upcoming,
        txns.observeRecent(6),
    ) { acc, md, lt, up, recent ->
        HomeState(false, acc.third, acc.first, acc.second, md.first, md.second, lt, up, recent, Dates.today())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
