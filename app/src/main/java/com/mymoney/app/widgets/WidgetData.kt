package com.mymoney.app.widgets

import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BudgetWithSpent
import com.mymoney.app.data.db.CategorySpend
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.LoanDueItem
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.db.UpcomingItem
import com.mymoney.app.data.db.WidgetPreferencesEntity
import com.mymoney.app.notifications.ReminderEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Formats amounts for widgets, masking them in privacy mode. */
data class WidgetFormat(val currency: String, val masked: Boolean) {
    fun money(minor: Long): String = if (masked) Money.symbolFor(currency) + " ••••" else Money.format(minor, currency)
    fun compact(minor: Long): String = if (masked) "••••" else Money.compact(minor, currency)
}

data class BalanceModel(
    val availableMinor: Long, val bankMinor: Long, val cashMinor: Long, val cardOutstandingMinor: Long,
    val fmt: WidgetFormat, val hidden: Boolean, val updatedAt: Long,
)

data class MonthModel(
    val month: YearMonth, val incomeMinor: Long, val expenseMinor: Long, val categories: List<CategorySpend>, val fmt: WidgetFormat,
) {
    val netMinor get() = incomeMinor - expenseMinor
    /** Share of income spent, 0..1 (1 when there's spending but no income). */
    val spentShare: Float get() = when {
        incomeMinor <= 0 -> if (expenseMinor > 0) 1f else 0f
        else -> (expenseMinor.toFloat() / incomeMinor).coerceIn(0f, 1f)
    }
}

data class UpcomingRow(val id: Long, val title: String, val category: ScheduleCategory, val amountMinor: Long, val due: LocalDate, val overdue: Boolean, val dueLabel: String)
data class UpcomingModel(val rows: List<UpcomingRow>, val overdueCount: Int, val fmt: WidgetFormat)

data class PeopleRow(val loanId: Long, val person: String, val gave: Boolean, val outstandingMinor: Long, val due: LocalDate, val overdue: Boolean, val dueLabel: String)
data class PeopleModel(val toReceiveMinor: Long, val toPayMinor: Long, val rows: List<PeopleRow>, val fmt: WidgetFormat) {
    val netMinor get() = toReceiveMinor - toPayMinor
}

data class BudgetRow(val name: String, val colorArgb: Long, val spentMinor: Long, val limitMinor: Long) {
    val fraction: Float get() = if (limitMinor <= 0) 0f else (spentMinor.toFloat() / limitMinor).coerceIn(0f, 1f)
    val over: Boolean get() = spentMinor > limitMinor
    val percent: Int get() = Money.percent(spentMinor, limitMinor)
}
data class BudgetModel(val month: YearMonth, val rows: List<BudgetRow>, val fmt: WidgetFormat) {
    val limitMinor get() = rows.sumOf { it.limitMinor }
    val spentMinor get() = rows.sumOf { it.spentMinor }
    val remainingMinor get() = limitMinor - spentMinor
    val overCount get() = rows.count { it.over }
    val fraction: Float get() = if (limitMinor <= 0) 0f else (spentMinor.toFloat() / limitMinor).coerceIn(0f, 1f)
}

data class CardRow(val id: Long, val name: String, val outstandingMinor: Long, val limitMinor: Long?, val nextDue: LocalDate?)
data class CardModel(val cards: List<CardRow>, val fmt: WidgetFormat, val today: LocalDate) {
    val outstandingMinor get() = cards.sumOf { it.outstandingMinor }
    /** Unused credit only; never counted as available cash anywhere in the app. */
    val availableCreditMinor get() = cards.sumOf { c -> c.limitMinor?.let { (it - c.outstandingMinor).coerceAtLeast(0) } ?: 0 }
    val nextDue: CardRow? get() = cards.filter { it.nextDue != null && it.outstandingMinor > 0 }.minByOrNull { it.nextDue!! }
}

/** Pure builders, shared by the widgets and unit tests. */
object WidgetModels {
    fun balance(accounts: List<AccountWithBalance>, fmt: WidgetFormat, hidden: Boolean, now: Long): BalanceModel {
        val active = accounts.filter { !it.account.archived }
        val bank = active.filter { it.account.type == AccountType.BANK }.sumOf { it.balanceMinor }
        val cash = active.filter { it.account.type == AccountType.CASH }.sumOf { it.balanceMinor }
        val cards = active.filter { it.account.type == AccountType.CREDIT_CARD }.sumOf { it.outstandingMinor }
        // Available = bank + cash. Credit limits are never treated as money.
        return BalanceModel(bank + cash, bank, cash, cards, fmt, hidden, now)
    }

    fun upcoming(items: List<UpcomingItem>, today: LocalDate, fmt: WidgetFormat) = UpcomingModel(
        items.map {
            val due = LocalDate.ofEpochDay(it.nextDueEpochDay)
            UpcomingRow(it.id, it.title, it.category, it.amountMinor, due, due.isBefore(today), Dates.dueLabel(due, today))
        },
        items.count { it.nextDueEpochDay < today.toEpochDay() }, fmt,
    )

    fun people(toReceive: Long, toPay: Long, items: List<LoanDueItem>, today: LocalDate, fmt: WidgetFormat) = PeopleModel(
        toReceive, toPay,
        items.filter { it.outstandingMinor > 0 }.map {
            val due = LocalDate.ofEpochDay(it.dueEpochDay)
            PeopleRow(it.id, it.personName, it.direction == LoanDirection.GAVE, it.outstandingMinor, due, due.isBefore(today), Dates.dueLabel(due, today))
        },
        fmt,
    )

    fun budget(month: YearMonth, budgets: List<BudgetWithSpent>, fmt: WidgetFormat) =
        BudgetModel(month, budgets.map { BudgetRow(it.categoryName, it.categoryColor, it.spentMinor, it.budget.limitMinor) }, fmt)

    fun cards(accounts: List<AccountWithBalance>, today: LocalDate, fmt: WidgetFormat) = CardModel(
        accounts.filter { it.account.type == AccountType.CREDIT_CARD && !it.account.archived }.map {
            CardRow(it.account.id, it.account.name, it.outstandingMinor.coerceAtLeast(0), it.account.creditLimitMinor,
                it.account.dueDay?.let { d -> ReminderEngine.nextDueDate(d, today) })
        }.sortedByDescending { it.outstandingMinor },
        fmt, today,
    )
}

/** Reads snapshots for the widgets from Room (the single source of truth). */
@Singleton
/** The user's default UPI ID for the "My UPI QR" widget; [hidden] when widget privacy mode is on. */
data class UpiQrModel(val name: String, val vpa: String, val payload: String, val hidden: Boolean)

class WidgetRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.widgetDao()

    /** Saved UPI IDs (set by Hilt; absent in tests that don't need it). */
    @set:Inject var upi: com.mymoney.app.data.repo.MyUpiRepository? = null

    suspend fun upiQr(): UpiQrModel? {
        val id = upi?.ids?.first()?.firstOrNull() ?: return null
        return UpiQrModel(id.name, id.vpa, com.mymoney.app.autocapture.UpiUri.receiveUri(id.vpa, id.name), prefs().privacyMode)
    }

    fun observePrefs(): Flow<WidgetPreferencesEntity> = dao.observePrefs().map { it ?: WidgetPreferencesEntity() }
    suspend fun prefs(): WidgetPreferencesEntity = dao.getPrefs() ?: WidgetPreferencesEntity()
    suspend fun updatePrefs(t: (WidgetPreferencesEntity) -> WidgetPreferencesEntity) = dao.upsertPrefs(t(prefs()))
    suspend fun toggleBalanceHidden() = updatePrefs { it.copy(balanceHidden = !it.balanceHidden) }

    private suspend fun fmt(extraMask: Boolean = false): WidgetFormat {
        val s = db.settingsDao().get() ?: SettingsEntity()
        return WidgetFormat(s.currency, prefs().privacyMode || extraMask)
    }

    suspend fun balance(): BalanceModel {
        val p = prefs()
        return WidgetModels.balance(db.accountDao().getAllWithBalance(), fmt(p.balanceHidden), p.balanceHidden, Dates.nowMillis())
    }

    suspend fun month(ym: YearMonth = YearMonth.from(Dates.today())): MonthModel {
        val r = DateRange.month(ym)
        return MonthModel(
            ym, dao.sum("INCOME", r.startMillis, r.endMillisExclusive), dao.sum("EXPENSE", r.startMillis, r.endMillisExclusive),
            dao.categorySpend(r.startMillis, r.endMillisExclusive, 4), fmt(),
        )
    }

    suspend fun upcoming(limit: Int = 6) = WidgetModels.upcoming(dao.upcoming(limit), Dates.today(), fmt())

    suspend fun people(limit: Int = 5) = WidgetModels.people(
        dao.loanOutstanding(LoanDirection.GAVE.name), dao.loanOutstanding(LoanDirection.RECEIVED.name), dao.loansWithDue(limit), Dates.today(), fmt(),
    )

    suspend fun budget(ym: YearMonth = YearMonth.from(Dates.today())): BudgetModel {
        val r = DateRange.month(ym)
        return WidgetModels.budget(ym, dao.budgets(Dates.ymKey(ym), r.startMillis, r.endMillisExclusive), fmt())
    }

    suspend fun cards() = WidgetModels.cards(db.accountDao().getAllWithBalance(), Dates.today(), fmt())

    suspend fun markRefreshed() = updatePrefs { it.copy(lastRefreshedAt = Dates.nowMillis()) }
}
