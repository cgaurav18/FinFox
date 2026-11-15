package com.mymoney.app.data.repo

import com.mymoney.app.core.Dates
import com.mymoney.app.core.DateRange
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.NotificationReminderEntity
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.domain.Reports
import com.mymoney.app.notifications.Notifier
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a budget alert the first time a category's monthly spending crosses 80%, 90% and 100%.
 * Thresholds already announced are recorded in notification_reminders so they never repeat.
 */
@Singleton
class BudgetAlertChecker @Inject constructor(private val db: AppDatabase, private val notifier: Notifier) {

    /** Returns the threshold that was newly announced, if any. */
    suspend fun check(categoryId: Long?, dateMillis: Long): Int? {
        if (categoryId == null) return null
        val ym = YearMonth.from(Dates.toLocalDate(dateMillis))
        val budget = db.budgetDao().getFor(categoryId, Dates.ymKey(ym)) ?: return null
        val range = DateRange.month(ym)
        val spent = db.transactionDao().expenseForCategory(categoryId, range.startMillis, range.endMillisExclusive)
        val reached = Reports.budgetThreshold(spent, budget.limitMinor) ?: return null
        var newlyCrossed: Int? = null
        // 101 = spending went over the limit (distinct from exactly reaching 100%).
        val levels = listOf(80, 90, 100).filter { it <= reached } + if (spent > budget.limitMinor) listOf(EXCEEDED) else emptyList()
        // Record every threshold up to the one reached; announce only the highest new one.
        for (t in levels) {
            val id = db.reminderDao().insertIgnore(
                NotificationReminderEntity(refType = ReminderRefType.BUDGET, refId = budget.id, dueKey = t.toLong(), notifiedAt = Dates.nowMillis())
            )
            if (id != -1L) newlyCrossed = t
        }
        val settings = db.settingsDao().get()
        if (newlyCrossed != null && (settings?.budgetAlertsEnabled != false) && (settings?.notificationsEnabled != false)) {
            val cat = db.categoryDao().getById(categoryId)
            notifier.budgetAlert(budget.id, cat?.name ?: "Budget", newlyCrossed, spent, budget.limitMinor, settings?.currency ?: "INR")
        }
        return newlyCrossed
    }

    companion object { const val EXCEEDED = 101 }
}
