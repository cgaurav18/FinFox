package com.mymoney.app.notifications

import java.time.LocalDate

/** Abstraction over system notifications so business logic stays testable. */
interface Notifier {
    fun budgetAlert(budgetId: Long, categoryName: String, percent: Int, spentMinor: Long, limitMinor: Long, currency: String)
    fun scheduleReminder(scheduleId: Long, title: String, amountMinor: Long, due: LocalDate, overdue: Boolean, currency: String)
    fun loanReminder(loanId: Long, person: String, outstandingMinor: Long, gave: Boolean, due: LocalDate, currency: String)
}
