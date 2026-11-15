package com.mymoney.app.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.OccurrenceStatus
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.ScheduleStatus
import com.mymoney.app.data.db.ScheduledPaymentEntity
import com.mymoney.app.data.db.ScheduledPaymentOccurrenceEntity
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.domain.ScheduleMath
import com.mymoney.app.domain.TransactionRules
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleInput(
    val id: Long? = null,
    val title: String,
    val category: ScheduleCategory,
    val amountMinor: Long,
    val accountId: Long?,
    /** Set for credit card bills / transfers (e.g. SIP into an investment account). */
    val toAccountId: Long? = null,
    val expenseCategoryId: Long? = null,
    val start: LocalDate,
    val end: LocalDate? = null,
    val frequency: Frequency,
    val nextDue: LocalDate? = null,
    val reminderDaysBefore: Int = 1,
    val notes: String? = null,
    val active: Boolean = true,
)

@Singleton
class ScheduleRepository @Inject constructor(
    private val db: AppDatabase,
    private val budgetAlerts: BudgetAlertChecker,
) {
    private val dao = db.scheduleDao()

    fun observeAll() = dao.observeAll()
    fun observe(id: Long) = dao.observe(id)
    fun observeOccurrences(id: Long) = dao.observeOccurrences(id)
    suspend fun get(id: Long) = dao.getById(id)

    private suspend fun defaultCategoryId(c: ScheduleCategory): Long? {
        val name = when (c) {
            ScheduleCategory.EMI -> "EMI"
            ScheduleCategory.RENT -> "Rent"
            ScheduleCategory.ELECTRICITY, ScheduleCategory.INTERNET, ScheduleCategory.MOBILE, ScheduleCategory.INSURANCE -> "Bills"
            ScheduleCategory.SUBSCRIPTION -> "Entertainment"
            else -> "Other"
        }
        return db.categoryDao().findByName(name, CategoryType.EXPENSE.name)?.id
            ?: db.categoryDao().findByName("Other", CategoryType.EXPENSE.name)?.id
    }

    suspend fun save(i: ScheduleInput): Long = db.withTransaction {
        if (i.title.isBlank()) throw FinanceException("Enter a title")
        if (i.amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        val from = i.accountId?.let { db.accountDao().getById(it) } ?: throw FinanceException("Select the payment account")
        val existing = i.id?.let { dao.getById(it) ?: throw FinanceException("Schedule not found") }
        if (from.archived && from.id != existing?.accountId) throw FinanceException("${from.name} is archived")
        val to = i.toAccountId?.let { db.accountDao().getById(it) ?: throw FinanceException("Destination account not found") }
        if (i.category == ScheduleCategory.CREDIT_CARD_BILL && to?.type != AccountType.CREDIT_CARD) {
            throw FinanceException("Choose the credit card this bill pays")
        }
        var categoryId: Long? = null
        if (to != null) {
            TransactionRules.validate(TxnType.TRANSFER, i.amountMinor, from, to, null, isNew = existing == null,
                previousAccountIds = setOfNotNull(existing?.accountId, existing?.toAccountId))?.let { throw FinanceException(it) }
        } else {
            categoryId = i.expenseCategoryId ?: defaultCategoryId(i.category)
            val cat = categoryId?.let { db.categoryDao().getById(it) } ?: throw FinanceException("Select a category")
            if (cat.type != CategoryType.EXPENSE) throw FinanceException("Choose an expense category")
        }
        if (i.end != null && i.end.isBefore(i.start)) throw FinanceException("End date must be after the start date")
        if (i.reminderDaysBefore !in 0..30) throw FinanceException("Reminder must be 0-30 days before")
        val nextDue = i.nextDue ?: run {
            val today = Dates.today()
            if (existing != null && existing.startEpochDay == i.start.toEpochDay() && existing.frequency == i.frequency) {
                LocalDate.ofEpochDay(existing.nextDueEpochDay)
            } else ScheduleMath.firstOnOrAfter(i.start, i.frequency, maxOf(i.start, today))
        }
        if (nextDue.isBefore(i.start)) throw FinanceException("Next due date can't be before the start date")
        if (i.end != null && nextDue.isAfter(i.end)) throw FinanceException("Next due date is after the end date")
        val now = Dates.nowMillis()
        val status = when {
            !i.active -> ScheduleStatus.PAUSED
            else -> ScheduleStatus.ACTIVE
        }
        val entity = ScheduledPaymentEntity(
            id = existing?.id ?: 0, title = i.title.trim(), category = i.category, amountMinor = i.amountMinor,
            accountId = from.id, toAccountId = to?.id, expenseCategoryId = categoryId, startEpochDay = i.start.toEpochDay(),
            endEpochDay = i.end?.toEpochDay(), frequency = i.frequency, nextDueEpochDay = nextDue.toEpochDay(),
            reminderDaysBefore = i.reminderDaysBefore, notes = i.notes?.trim()?.takeIf { it.isNotEmpty() }, status = status,
            createdAt = existing?.createdAt ?: now, updatedAt = now,
        )
        if (existing == null) dao.insert(entity) else { dao.update(entity); existing.id }
    }

    /** Used by "Recurring" on Add Transaction: the first installment is the transaction itself. */
    internal suspend fun createFromTransaction(t: TransactionEntity, frequency: Frequency, toType: AccountType?) {
        val date = Dates.toLocalDate(t.dateMillis)
        val start = ScheduleMath.nth(date, frequency, 1)
        val catName = t.categoryId?.let { db.categoryDao().getById(it)?.name }
        val category = when {
            toType == AccountType.CREDIT_CARD -> ScheduleCategory.CREDIT_CARD_BILL
            catName == "EMI" -> ScheduleCategory.EMI
            catName == "Rent" -> ScheduleCategory.RENT
            else -> ScheduleCategory.CUSTOM
        }
        val now = Dates.nowMillis()
        dao.insert(
            ScheduledPaymentEntity(
                title = t.merchant ?: catName ?: "Recurring payment", category = category, amountMinor = t.amountMinor,
                accountId = t.accountId, toAccountId = t.toAccountId, expenseCategoryId = t.categoryId,
                startEpochDay = start.toEpochDay(), frequency = frequency, nextDueEpochDay = start.toEpochDay(),
                notes = t.note, createdAt = now, updatedAt = now,
            )
        )
    }

    /**
     * Records an installment as paid: creates the linked transaction and advances the schedule, atomically.
     * The unique (scheduleId, dueEpochDay) index guarantees the same installment can't be paid twice.
     */
    suspend fun markPaid(
        scheduleId: Long,
        due: LocalDate? = null,
        amountMinor: Long? = null,
        accountId: Long? = null,
        paidOn: LocalDate = Dates.today(),
    ): Long {
        var categoryForAlert: Long? = null
        var paidAt = 0L
        val txnId = db.withTransaction {
            val s = dao.getById(scheduleId) ?: throw FinanceException("Schedule not found")
            val dueDate = due ?: LocalDate.ofEpochDay(s.nextDueEpochDay)
            dao.getOccurrence(scheduleId, dueDate.toEpochDay())?.let {
                throw FinanceException(if (it.status == OccurrenceStatus.PAID) "This installment is already paid" else "This installment was skipped")
            }
            val amount = amountMinor ?: s.amountMinor
            val from = db.accountDao().getById(accountId ?: s.accountId) ?: throw FinanceException("Account not found")
            val to = s.toAccountId?.let { db.accountDao().getById(it) }
            val type = if (to != null) TxnType.TRANSFER else TxnType.EXPENSE
            val cat = s.expenseCategoryId?.let { db.categoryDao().getById(it) }
            TransactionRules.validate(type, amount, from, to, if (type == TxnType.EXPENSE) cat else null, isNew = true)?.let { throw FinanceException(it) }
            val now = Dates.nowMillis()
            paidAt = Dates.toMillis(paidOn, if (paidOn == Dates.today()) LocalTime.now() else LocalTime.NOON)
            val id = db.transactionDao().insert(
                TransactionEntity(
                    type = type, amountMinor = amount, accountId = from.id, toAccountId = to?.id,
                    categoryId = if (type == TxnType.EXPENSE) cat?.id else null, dateMillis = paidAt, merchant = s.title,
                    note = "Scheduled: ${s.title} (due ${Dates.formatDay(dueDate)})", scheduleId = s.id, createdAt = now, updatedAt = now,
                )
            )
            try {
                dao.insertOccurrence(ScheduledPaymentOccurrenceEntity(scheduleId = s.id, dueEpochDay = dueDate.toEpochDay(), status = OccurrenceStatus.PAID, transactionId = id, amountMinor = amount, actedAt = now))
            } catch (e: SQLiteConstraintException) {
                throw FinanceException("This installment is already paid")
            }
            advance(s, dueDate)
            categoryForAlert = if (type == TxnType.EXPENSE) cat?.id else null
            id
        }
        budgetAlerts.check(categoryForAlert, paidAt)
        return txnId
    }

    /** Skips an installment without creating a transaction. */
    suspend fun skip(scheduleId: Long, due: LocalDate? = null) = db.withTransaction {
        val s = dao.getById(scheduleId) ?: throw FinanceException("Schedule not found")
        val dueDate = due ?: LocalDate.ofEpochDay(s.nextDueEpochDay)
        if (dao.getOccurrence(scheduleId, dueDate.toEpochDay()) != null) throw FinanceException("This installment is already handled")
        dao.insertOccurrence(ScheduledPaymentOccurrenceEntity(scheduleId = s.id, dueEpochDay = dueDate.toEpochDay(), status = OccurrenceStatus.SKIPPED, amountMinor = 0, actedAt = Dates.nowMillis()))
        advance(s, dueDate)
    }

    private suspend fun advance(s: ScheduledPaymentEntity, handledDue: LocalDate) {
        if (handledDue.toEpochDay() != s.nextDueEpochDay) return
        val start = LocalDate.ofEpochDay(s.startEpochDay)
        var next = ScheduleMath.nextAfter(start, s.frequency, handledDue)
        while (dao.getOccurrence(s.id, next.toEpochDay()) != null) next = ScheduleMath.nextAfter(start, s.frequency, next)
        val end = s.endEpochDay?.let { LocalDate.ofEpochDay(it) }
        val status = if (end != null && next.isAfter(end)) ScheduleStatus.COMPLETED else s.status
        dao.update(s.copy(nextDueEpochDay = next.toEpochDay(), status = status, updatedAt = Dates.nowMillis()))
    }

    /** Called when a paid installment's transaction is deleted: the installment is due again. */
    internal suspend fun reopenInstallment(scheduleId: Long, due: Long) {
        val s = dao.getById(scheduleId) ?: return
        if (due < s.nextDueEpochDay || s.status == ScheduleStatus.COMPLETED) {
            dao.update(
                s.copy(
                    nextDueEpochDay = minOf(due, s.nextDueEpochDay),
                    status = if (s.status == ScheduleStatus.COMPLETED) ScheduleStatus.ACTIVE else s.status,
                    updatedAt = Dates.nowMillis(),
                )
            )
        }
    }

    suspend fun setPaused(id: Long, paused: Boolean) = db.withTransaction {
        val s = dao.getById(id) ?: throw FinanceException("Schedule not found")
        if (s.status == ScheduleStatus.COMPLETED) throw FinanceException("This schedule has ended")
        dao.update(s.copy(status = if (paused) ScheduleStatus.PAUSED else ScheduleStatus.ACTIVE, updatedAt = Dates.nowMillis()))
    }

    /** Deletes the schedule and its installment log. Paid transactions stay in history. */
    suspend fun delete(id: Long) = db.withTransaction {
        dao.getById(id) ?: throw FinanceException("Schedule not found")
        dao.deleteById(id)
        db.reminderDao().deleteFor(ReminderRefType.SCHEDULE.name, id)
    }
}
