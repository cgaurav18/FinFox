package com.mymoney.app.notifications

import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.NotificationCategory
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.data.db.ScheduleCategory
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Builds user-facing notification content; [NotificationDispatcher] decides whether/how it's shown. */
@Singleton
class AndroidNotifier @Inject constructor(private val dispatcher: NotificationDispatcher) : Notifier {
    companion object {
        /** Kept for callers that only need the route extra name. */
        const val EXTRA_ROUTE = NotificationDispatcher.EXTRA_ROUTE
        const val ID_SCHEDULE = 100_000
        const val ID_LOAN = 200_000
        const val ID_BUDGET = 300_000
        const val ID_GOAL = 400_000
        const val ID_CARD = 500_000
        const val ID_BACKUP = 600_000
        const val ID_REPAYMENT = 700_000
        const val ID_CAPTURE = 800_000
    }

    private fun m(v: Long, c: String) = Money.format(v, c)
    private fun day(d: LocalDate) = Dates.formatDay(d)

    override suspend fun budgetAlert(budgetId: Long, categoryName: String, percent: Int, spentMinor: Long, limitMinor: Long, currency: String) {
        val title = when {
            percent > 100 -> "$categoryName budget exceeded"
            percent == 100 -> "$categoryName budget reached"
            else -> "$categoryName budget at $percent%"
        }
        val over = spentMinor - limitMinor
        val text = if (over > 0) "Spent ${m(spentMinor, currency)} of ${m(limitMinor, currency)} — ${m(over, currency)} over budget this month."
        else "Spent ${m(spentMinor, currency)} of ${m(limitMinor, currency)} this month. ${m(limitMinor - spentMinor, currency)} left."
        dispatcher.post(
            NotificationSpec((ID_BUDGET + budgetId).toInt(), NotificationCategory.BUDGET, title, text,
                title, "Open FinFox to see your budget.", "budgets", ReminderRefType.BUDGET.name, budgetId)
        )
    }

    override suspend fun scheduleReminder(scheduleId: Long, title: String, amountMinor: Long, due: LocalDate, overdue: Boolean, currency: String, category: ScheduleCategory) {
        val head = if (overdue) "Overdue: $title" else "$title ${Dates.dueLabel(due).replaceFirstChar { it.lowercase() }}"
        val kind = when (category) {
            ScheduleCategory.EMI -> "EMI"; ScheduleCategory.SIP -> "SIP"; ScheduleCategory.CREDIT_CARD_BILL -> "Card bill"
            ScheduleCategory.SUBSCRIPTION -> "Subscription renewal"; ScheduleCategory.RENT -> "Rent"; ScheduleCategory.ELECTRICITY -> "Electricity bill"
            else -> "Payment"
        }
        dispatcher.post(
            NotificationSpec(
                (ID_SCHEDULE + scheduleId).toInt(), NotificationCategory.PAYMENT, head,
                "$kind of ${m(amountMinor, currency)} due on ${day(due)}. Nothing is paid automatically — mark it as paid once you've paid.",
                if (overdue) "Overdue $kind" else "$kind ${Dates.dueLabel(due).replaceFirstChar { it.lowercase() }}",
                "Open FinFox to see the details.",
                "schedule/$scheduleId", ReminderRefType.SCHEDULE.name, scheduleId,
                listOf(NotificationAction.MARK_PAID, NotificationAction.REMIND_LATER, NotificationAction.DISMISS),
            )
        )
    }

    override suspend fun loanReminder(loanId: Long, person: String, outstandingMinor: Long, gave: Boolean, due: LocalDate, currency: String) {
        val overdue = due.isBefore(Dates.today())
        val head = when {
            gave && overdue -> "$person's repayment is overdue"
            gave -> "Money expected from $person"
            overdue -> "Overdue: you owe $person"
            else -> "Repayment due to $person"
        }
        val text = (if (gave) "$person owes you ${m(outstandingMinor, currency)}" else "You owe $person ${m(outstandingMinor, currency)}") +
            ". ${Dates.dueLabel(due)} (${day(due)})."
        dispatcher.post(
            NotificationSpec((ID_LOAN + loanId).toInt(), NotificationCategory.LOAN, head, text,
                if (gave) "Money expected" else "Repayment due", "Open FinFox to see the details.",
                "loan/$loanId", ReminderRefType.LOAN.name, loanId,
                listOf(NotificationAction.VIEW, NotificationAction.REMIND_LATER, NotificationAction.DISMISS))
        )
    }

    override suspend fun cardDueReminder(accountId: Long, cardName: String, outstandingMinor: Long, due: LocalDate, currency: String) {
        val title = "$cardName bill ${Dates.dueLabel(due).replaceFirstChar { it.lowercase() }}"
        dispatcher.post(
            NotificationSpec((ID_CARD + accountId).toInt(), NotificationCategory.PAYMENT, title,
                "Outstanding ${m(outstandingMinor, currency)}, due ${day(due)}. Record the bill payment as a transfer to the card.",
                "Credit card bill due", "Open FinFox to see the details.",
                "account-detail/$accountId", ReminderRefType.CARD.name, accountId,
                listOf(NotificationAction.VIEW, NotificationAction.REMIND_LATER, NotificationAction.DISMISS))
        )
    }

    override suspend fun repaymentRecorded(loanId: Long, person: String, amountMinor: Long, outstandingMinor: Long, gave: Boolean, currency: String) {
        val title = if (outstandingMinor > 0) "Partial repayment recorded" else "Fully repaid"
        val text = (if (gave) "$person repaid ${m(amountMinor, currency)}." else "You repaid ${m(amountMinor, currency)} to $person.") +
            if (outstandingMinor > 0) " ${m(outstandingMinor, currency)} still outstanding." else " Nothing is outstanding."
        dispatcher.post(
            NotificationSpec((ID_REPAYMENT + loanId).toInt(), NotificationCategory.LOAN, title, text, title,
                "Open FinFox to see the details.", "loan/$loanId", ReminderRefType.LOAN.name, loanId)
        )
    }

    override suspend fun goalAlert(goalId: Long, name: String, alert: GoalAlert, savedMinor: Long, targetMinor: Long, targetDate: LocalDate?, currency: String) {
        val (title, text) = when (alert) {
            GoalAlert.COMPLETED -> "Goal completed: $name 🎉" to "You've saved ${m(savedMinor, currency)} of ${m(targetMinor, currency)}."
            GoalAlert.TARGET_SOON -> "$name target date is near" to
                "${m((targetMinor - savedMinor).coerceAtLeast(0), currency)} still to save by ${targetDate?.let(::day) ?: "the target date"}."
            else -> "$name is ${alert.percent}% funded" to "Saved ${m(savedMinor, currency)} of ${m(targetMinor, currency)}. Keep going!"
        }
        dispatcher.post(
            NotificationSpec((ID_GOAL + goalId).toInt(), NotificationCategory.GOAL, title, text, "Savings goal update",
                "Open FinFox to see your progress.", "goal/$goalId", ReminderRefType.GOAL.name, goalId)
        )
    }

    override suspend fun backupEvent(event: BackupEvent, detail: String?) {
        val (title, text) = when (event) {
            BackupEvent.SUCCESS -> "Backup complete" to (detail ?: "Your data was backed up to Google Drive.")
            BackupEvent.FAILED -> "Backup failed" to (detail ?: "The Google Drive backup couldn't be completed.")
            BackupEvent.RESTORED -> "Restore completed" to (detail ?: "Your data was restored.")
            BackupEvent.AUTH_EXPIRED -> "Google Drive authorization expired" to "Open FinFox and reconnect Google Drive to keep backing up."
        }
        dispatcher.post(
            NotificationSpec(ID_BACKUP + event.ordinal, NotificationCategory.BACKUP, title, text, title, text, "backup",
                actions = if (event == BackupEvent.AUTH_EXPIRED || event == BackupEvent.FAILED) listOf(NotificationAction.RECONNECT, NotificationAction.DISMISS)
                else listOf(NotificationAction.VIEW, NotificationAction.DISMISS))
        )
    }

    override suspend fun paymentCaptured(captureId: Long, transactionId: Long?, debit: Boolean, amountMinor: Long, counterparty: String?, categoryName: String?, currency: String) {
        val who = counterparty?.let { if (debit) " to $it" else " from $it" } ?: ""
        val amount = m(amountMinor, currency)
        val (title, text) = if (transactionId != null) {
            (if (debit) "Expense added: $amount$who" else "Income added: $amount$who") to
                "Recorded automatically${categoryName?.let { " under $it" } ?: ""}. Tap to change the category or account."
        } else {
            (if (debit) "Review: $amount spent$who" else "Review: $amount received$who") to
                "FinFox found this payment but needs you to confirm the account or category."
        }
        dispatcher.post(
            NotificationSpec(
                ID_CAPTURE + (captureId % 99_999).toInt(), NotificationCategory.CAPTURE, title, text,
                if (transactionId != null) "Payment recorded" else "Payment to review", "Open FinFox to see the details.",
                if (transactionId != null) "txn-detail/$transactionId" else "auto-capture",
            )
        )
    }
}
