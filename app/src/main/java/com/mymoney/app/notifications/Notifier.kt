package com.mymoney.app.notifications

import com.mymoney.app.data.db.ScheduleCategory
import java.time.LocalDate

enum class GoalAlert(val percent: Int) { MILESTONE_25(25), MILESTONE_50(50), MILESTONE_75(75), COMPLETED(100), TARGET_SOON(-1) }

enum class BackupEvent { SUCCESS, FAILED, RESTORED, AUTH_EXPIRED }

/**
 * Abstraction over system notifications so business logic stays testable. Every call is a request:
 * the implementation applies the user's notification preferences, quiet hours and privacy settings.
 */
interface Notifier {
    /** [percent] is 80, 90 or 100 (reached), or 101 when spending is over the limit. */
    suspend fun budgetAlert(budgetId: Long, categoryName: String, percent: Int, spentMinor: Long, limitMinor: Long, currency: String)
    suspend fun scheduleReminder(scheduleId: Long, title: String, amountMinor: Long, due: LocalDate, overdue: Boolean, currency: String, category: ScheduleCategory = ScheduleCategory.CUSTOM)
    suspend fun loanReminder(loanId: Long, person: String, outstandingMinor: Long, gave: Boolean, due: LocalDate, currency: String)
    suspend fun cardDueReminder(accountId: Long, cardName: String, outstandingMinor: Long, due: LocalDate, currency: String) {}
    suspend fun repaymentRecorded(loanId: Long, person: String, amountMinor: Long, outstandingMinor: Long, gave: Boolean, currency: String) {}
    suspend fun goalAlert(goalId: Long, name: String, alert: GoalAlert, savedMinor: Long, targetMinor: Long, targetDate: LocalDate?, currency: String) {}
    suspend fun backupEvent(event: BackupEvent, detail: String?) {}
}
