package com.mymoney.app.ui.reports

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.mymoney.app.backup.Exporter
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.PeriodFilter
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.BudgetWithSpent
import com.mymoney.app.data.db.GoalWithSaved
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.repo.AccountRepository
import com.mymoney.app.data.repo.BudgetRepository
import com.mymoney.app.data.repo.CategoryRepository
import com.mymoney.app.data.repo.GoalRepository
import com.mymoney.app.data.repo.LoanRepository
import com.mymoney.app.data.repo.ScheduleRepository
import com.mymoney.app.data.repo.SettingsRepository
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.domain.Reports
import com.mymoney.app.domain.ScheduleMath
import com.mymoney.app.domain.SeriesPoint
import com.mymoney.app.domain.Slice
import com.mymoney.app.domain.Summary
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class ReportFilter(
    val period: PeriodFilter = PeriodFilter.THIS_MONTH,
    val custom: DateRange? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
) {
    val range: DateRange get() = period.range(Dates.today(), custom)
}

data class ScheduleSummary(val active: Int, val paused: Int, val overdue: Int, val dueInPeriodMinor: Long, val paidInPeriodMinor: Long, val paidCount: Int)

data class ReportState(
    val loading: Boolean = true,
    val txns: List<TransactionDetail> = emptyList(),
    val summary: Summary = Summary(0, 0, 0),
    val series: List<SeriesPoint> = emptyList(),
    val cumulative: List<Long> = emptyList(),
    val cumulativeLabels: List<String> = emptyList(),
    val categories: List<Slice> = emptyList(),
    val incomeCategories: List<Slice> = emptyList(),
    val accountSpending: List<Slice> = emptyList(),
    val cards: List<AccountWithBalance> = emptyList(),
    val budgets: List<BudgetWithSpent> = emptyList(),
    val budgetMonth: YearMonth = YearMonth.now(),
    val schedules: ScheduleSummary = ScheduleSummary(0, 0, 0, 0, 0, 0),
    val lent: Long = 0,
    val borrowed: Long = 0,
    val lentInPeriod: Long = 0,
    val borrowedInPeriod: Long = 0,
    val goals: List<GoalWithSaved> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val txns: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    budgets: BudgetRepository,
    schedules: ScheduleRepository,
    loans: LoanRepository,
    goals: GoalRepository,
    private val settings: SettingsRepository,
    private val exporter: Exporter,
    private val lock: AppLockManager,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val filter = MutableStateFlow(ReportFilter())
    val accounts = accounts.observeAccounts(true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.observe(null, true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filtered = filter.flatMapLatest { f ->
        combine(txns.observeInRange(f.range), kotlinx.coroutines.flow.flowOf(f)) { list, ff ->
            list.filter { (ff.accountId == null || it.txn.accountId == ff.accountId || it.txn.toAccountId == ff.accountId) && (ff.categoryId == null || it.txn.categoryId == ff.categoryId) } to ff
        }
    }

    private val budgetFlow = filter.flatMapLatest { budgets.observeMonth(YearMonth.from(it.range.endInclusive)) }

    private val extras = combine(
        accounts.observeAccounts(false), budgetFlow, schedules.observeAll(),
        combine(loans.observeOutstanding(LoanDirection.GAVE), loans.observeOutstanding(LoanDirection.RECEIVED)) { a, b -> a to b },
        goals.observeGoals(false),
    ) { acc, b, s, l, g -> Extras(acc, b, s, l, g) }

    private data class Extras(val accounts: List<AccountWithBalance>, val budgets: List<BudgetWithSpent>, val schedules: List<com.mymoney.app.data.db.ScheduleDetail>, val loans: Pair<Long, Long>, val goals: List<GoalWithSaved>)

    val state: StateFlow<ReportState> = combine(filtered, extras) { (list, f), ex ->
        val range = f.range
        val series = Reports.series(list, range)
        var run = 0L
        val cumulative = series.map { run += it.netMinor; run }
        val today = Dates.today()
        val active = ex.schedules.filter { it.schedule.status == ScheduleStatus.ACTIVE }
        val dueInPeriod = active.sumOf { d ->
            val s = d.schedule
            ScheduleMath.occurrencesBetween(LocalDate.ofEpochDay(s.startEpochDay), s.frequency, LocalDate.ofEpochDay(s.nextDueEpochDay), s.endEpochDay?.let(LocalDate::ofEpochDay), range.start, range.endInclusive).size * s.amountMinor
        }
        val paid = list.filter { it.txn.scheduleId != null }
        ReportState(
            loading = false, txns = list, summary = Reports.summary(list), series = series, cumulative = cumulative,
            cumulativeLabels = series.map { it.label },
            categories = Reports.categoryBreakdown(list), incomeCategories = Reports.categoryBreakdown(list, com.mymoney.app.data.db.TxnType.INCOME),
            accountSpending = Reports.accountSpending(list),
            cards = ex.accounts.filter { it.account.type == AccountType.CREDIT_CARD },
            budgets = ex.budgets, budgetMonth = YearMonth.from(range.endInclusive),
            schedules = ScheduleSummary(active.size, ex.schedules.count { it.schedule.status == ScheduleStatus.PAUSED },
                active.count { it.schedule.nextDueEpochDay < today.toEpochDay() }, dueInPeriod, paid.sumOf { it.txn.amountMinor }, paid.size),
            lent = ex.loans.first, borrowed = ex.loans.second,
            lentInPeriod = list.filter { it.txn.type == com.mymoney.app.data.db.TxnType.LOAN_GIVEN }.sumOf { it.txn.amountMinor },
            borrowedInPeriod = list.filter { it.txn.type == com.mymoney.app.data.db.TxnType.LOAN_TAKEN }.sumOf { it.txn.amountMinor },
            goals = ex.goals,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportState())

    /** Called right before opening the system file picker so returning from it doesn't trigger auto-lock. */
    fun beforeExternalActivity() { lock.suppressNextBackgroundLock = true }

    fun exportCsv(uri: Uri) = launchSafe("CSV exported") {
        exporter.writeCsv(uri, state.value.txns, settings.get().currency)
    }

    fun exportPdf(uri: Uri) = launchSafe("PDF report exported") {
        val s = settings.get()
        exporter.writePdf(uri, state.value.txns, filter.value.range, s.currency, s.profileName)
    }

    fun fileStem(): String {
        val r = filter.value.range
        return "FinFox_${r.start}_to_${r.endInclusive}"
    }
}
