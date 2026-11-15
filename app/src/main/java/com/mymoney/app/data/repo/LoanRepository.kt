package com.mymoney.app.data.repo

import androidx.room.withTransaction
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.LoanEntity
import com.mymoney.app.data.db.LoanRepaymentEntity
import com.mymoney.app.data.db.PersonEntity
import com.mymoney.app.data.db.ReminderRefType
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.TxnType
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class LoanInput(
    val id: Long? = null,
    val personName: String,
    val phone: String? = null,
    val direction: LoanDirection,
    val amountMinor: Long,
    val dateMillis: Long,
    val due: LocalDate? = null,
    val accountId: Long?,
    val description: String? = null,
    val reminder: Boolean = false,
)

sealed interface SettleMode {
    /** Records the remaining outstanding as a repayment into/out of [accountId]. */
    data class RecordRemaining(val accountId: Long) : SettleMode
    /** Closes the entry without moving money (forgiven / written off). */
    data object WriteOff : SettleMode
}

/**
 * Lending & borrowing. Lending ₹X from an account creates a LOAN_GIVEN ledger entry (account −X,
 * receivable +X) which is never counted as an expense; repayments create the opposite movement.
 */
@Singleton
class LoanRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.loanDao()
    private val txDao = db.transactionDao()

    fun observeLoans(direction: LoanDirection) = dao.observeLoans(direction.name)
    fun observeLoan(id: Long) = dao.observeLoan(id)
    fun observeRepayments(loanId: Long) = dao.observeRepayments(loanId)
    fun observePersonSummaries() = dao.observePersonSummaries()
    fun observePeople() = dao.observePeople()
    fun observePerson(id: Long) = dao.observePerson(id)
    fun observeLoansForPerson(personId: Long) = dao.observeLoansForPerson(personId)
    fun observeOutstanding(direction: LoanDirection) = dao.observeOutstanding(direction.name)
    fun observeOverdue(direction: LoanDirection, today: LocalDate = Dates.today()) = dao.observeOverdue(direction.name, today.toEpochDay())
    suspend fun getLoan(id: Long) = dao.getLoanDetail(id)
    suspend fun getRepayment(id: Long) = dao.getRepayment(id)

    private suspend fun requireAccount(id: Long?, allowArchivedId: Set<Long> = emptySet()): AccountEntity {
        val a = id?.let { db.accountDao().getById(it) } ?: throw FinanceException("Select an account")
        if (a.type == AccountType.CREDIT_CARD) throw FinanceException("Use a bank or cash account for lending and borrowing")
        if (a.archived && a.id !in allowArchivedId) throw FinanceException("${a.name} is archived")
        return a
    }

    private fun principalType(d: LoanDirection) = if (d == LoanDirection.GAVE) TxnType.LOAN_GIVEN else TxnType.LOAN_TAKEN
    private fun repaymentType(d: LoanDirection) = if (d == LoanDirection.GAVE) TxnType.LOAN_REPAYMENT_RECEIVED else TxnType.LOAN_REPAYMENT_PAID

    suspend fun saveLoan(i: LoanInput): Long = db.withTransaction {
        val name = i.personName.trim()
        if (name.isEmpty()) throw FinanceException("Enter the person's name")
        if (i.amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        if (i.amountMinor > Money.MAX_MINOR) throw FinanceException("Amount is too large")
        val phone = i.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null && !Regex("^[+0-9 ()-]{6,20}$").matches(phone)) throw FinanceException("Enter a valid phone number")
        val existing = i.id?.let { dao.getLoan(it) ?: throw FinanceException("Entry not found") }
        val account = requireAccount(i.accountId, setOfNotNull(existing?.accountId))
        val loanDate = Dates.toLocalDate(i.dateMillis)
        if (i.due != null && i.due.isBefore(loanDate)) throw FinanceException("Due date can't be before the date")
        val now = Dates.nowMillis()

        val person = dao.findPersonByName(name)?.let { p ->
            if (phone != null && p.phone != phone) p.copy(phone = phone).also { dao.updatePerson(it) } else p
        } ?: PersonEntity(id = dao.insertPerson(PersonEntity(name = name, phone = phone, createdAt = now)), name = name, phone = phone, createdAt = now)

        if (existing == null) {
            val loanId = dao.insertLoan(
                LoanEntity(
                    personId = person.id, direction = i.direction, amountMinor = i.amountMinor, dateMillis = i.dateMillis,
                    dueEpochDay = i.due?.toEpochDay(), accountId = account.id, description = i.description?.trim()?.takeIf { it.isNotEmpty() },
                    reminderEnabled = i.reminder && i.due != null, createdAt = now, updatedAt = now,
                )
            )
            txDao.insert(
                TransactionEntity(
                    type = principalType(i.direction), amountMinor = i.amountMinor, accountId = account.id, dateMillis = i.dateMillis,
                    merchant = person.name, note = i.description?.trim()?.takeIf { it.isNotEmpty() }, loanId = loanId, createdAt = now, updatedAt = now,
                )
            )
            loanId
        } else {
            val repaid = dao.repaidFor(existing.id)
            if (i.amountMinor < repaid) throw FinanceException("Amount can't be less than the ${Money.format(repaid)} already repaid")
            val settled = if (existing.settled && existing.writtenOffMinor > 0) existing.settled else repaid >= i.amountMinor
            val updated = existing.copy(
                personId = person.id, direction = i.direction, amountMinor = i.amountMinor, dateMillis = i.dateMillis,
                dueEpochDay = i.due?.toEpochDay(), accountId = account.id, description = i.description?.trim()?.takeIf { it.isNotEmpty() },
                reminderEnabled = i.reminder && i.due != null, settled = settled,
                writtenOffMinor = if (settled) existing.writtenOffMinor else 0, settledAt = if (settled) existing.settledAt ?: now else null,
                updatedAt = now,
            )
            if (existing.direction != i.direction && repaid > 0) throw FinanceException("Direction can't change after repayments are recorded")
            dao.updateLoan(updated)
            val principal = txDao.getLoanPrincipal(existing.id) ?: throw FinanceException("Ledger entry missing")
            txDao.update(principal.copy(type = principalType(i.direction), amountMinor = i.amountMinor, accountId = account.id, dateMillis = i.dateMillis, merchant = person.name, note = updated.description, updatedAt = now))
            if (existing.dueEpochDay != updated.dueEpochDay) db.reminderDao().deleteFor(ReminderRefType.LOAN.name, existing.id)
            existing.id
        }
    }

    /** Deletes the entry with all repayments and their ledger transactions; balances revert automatically. */
    suspend fun deleteLoan(id: Long) = db.withTransaction {
        dao.getLoan(id) ?: throw FinanceException("Entry not found")
        dao.deleteLoan(id)
        db.reminderDao().deleteFor(ReminderRefType.LOAN.name, id)
    }

    suspend fun addRepayment(loanId: Long, amountMinor: Long, dateMillis: Long, accountId: Long?, note: String?): Long = db.withTransaction {
        val loan = dao.getLoan(loanId) ?: throw FinanceException("Entry not found")
        if (loan.settled) throw FinanceException("This entry is already settled")
        if (amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        val outstanding = loan.amountMinor - dao.repaidFor(loanId)
        if (amountMinor > outstanding) throw FinanceException("Repayment exceeds the outstanding ${Money.format(outstanding)}")
        if (dateMillis < Dates.startOfDay(Dates.toLocalDate(loan.dateMillis))) throw FinanceException("Repayment can't be before the entry date")
        val account = requireAccount(accountId)
        val now = Dates.nowMillis()
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val rid = dao.insertRepayment(LoanRepaymentEntity(loanId = loanId, amountMinor = amountMinor, dateMillis = dateMillis, accountId = account.id, note = cleanNote, createdAt = now))
        val personName = dao.getPerson(loan.personId)?.name
        txDao.insert(TransactionEntity(type = repaymentType(loan.direction), amountMinor = amountMinor, accountId = account.id, dateMillis = dateMillis, merchant = personName, note = cleanNote ?: "Repayment", loanId = loanId, repaymentId = rid, createdAt = now, updatedAt = now))
        refreshSettled(loanId)
        rid
    }

    suspend fun updateRepayment(repaymentId: Long, amountMinor: Long, dateMillis: Long, accountId: Long?, note: String?) = db.withTransaction {
        val r = dao.getRepayment(repaymentId) ?: throw FinanceException("Repayment not found")
        val loan = dao.getLoan(r.loanId) ?: throw FinanceException("Entry not found")
        if (amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        val outstandingExcl = loan.amountMinor - (dao.repaidFor(loan.id) - r.amountMinor)
        if (amountMinor > outstandingExcl) throw FinanceException("Repayment exceeds the outstanding ${Money.format(outstandingExcl)}")
        val account = requireAccount(accountId, setOf(r.accountId))
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        dao.updateRepayment(r.copy(amountMinor = amountMinor, dateMillis = dateMillis, accountId = account.id, note = cleanNote))
        txDao.getByRepayment(r.id)?.let { txDao.update(it.copy(amountMinor = amountMinor, dateMillis = dateMillis, accountId = account.id, note = cleanNote ?: "Repayment", updatedAt = Dates.nowMillis())) }
        if (loan.writtenOffMinor > 0) dao.updateLoan(loan.copy(settled = false, writtenOffMinor = 0, settledAt = null))
        refreshSettled(loan.id)
    }

    suspend fun deleteRepayment(repaymentId: Long) = db.withTransaction {
        val r = dao.getRepayment(repaymentId) ?: throw FinanceException("Repayment not found")
        dao.deleteRepayment(repaymentId) // ledger transaction cascades
        val loan = dao.getLoan(r.loanId) ?: return@withTransaction
        if (loan.writtenOffMinor > 0) dao.updateLoan(loan.copy(settled = false, writtenOffMinor = 0, settledAt = null))
        refreshSettled(r.loanId)
    }

    suspend fun settle(loanId: Long, mode: SettleMode) = db.withTransaction {
        val loan = dao.getLoan(loanId) ?: throw FinanceException("Entry not found")
        if (loan.settled) throw FinanceException("Already settled")
        val outstanding = loan.amountMinor - dao.repaidFor(loanId)
        when (mode) {
            is SettleMode.RecordRemaining -> addRepayment(loanId, outstanding, Dates.nowMillis(), mode.accountId, "Final settlement")
            SettleMode.WriteOff -> dao.updateLoan(loan.copy(settled = true, writtenOffMinor = outstanding, settledAt = Dates.nowMillis(), updatedAt = Dates.nowMillis()))
        }
    }

    suspend fun reopen(loanId: Long) = db.withTransaction {
        val loan = dao.getLoan(loanId) ?: throw FinanceException("Entry not found")
        if (dao.repaidFor(loanId) >= loan.amountMinor) throw FinanceException("Fully repaid entries can't be reopened. Delete a repayment instead.")
        dao.updateLoan(loan.copy(settled = false, writtenOffMinor = 0, settledAt = null, updatedAt = Dates.nowMillis()))
    }

    private suspend fun refreshSettled(loanId: Long) {
        val loan = dao.getLoan(loanId) ?: return
        if (loan.writtenOffMinor > 0) return
        val full = dao.repaidFor(loanId) >= loan.amountMinor
        if (full != loan.settled) dao.updateLoan(loan.copy(settled = full, settledAt = if (full) Dates.nowMillis() else null, updatedAt = Dates.nowMillis()))
    }

    suspend fun updatePerson(id: Long, name: String, phone: String?) = db.withTransaction {
        val n = name.trim()
        if (n.isEmpty()) throw FinanceException("Enter a name")
        val p = dao.getPerson(id) ?: throw FinanceException("Person not found")
        dao.findPersonByName(n)?.let { if (it.id != id) throw FinanceException("Another person named $n exists") }
        dao.updatePerson(p.copy(name = n, phone = phone?.trim()?.takeIf { it.isNotEmpty() }))
    }

    suspend fun deletePerson(id: Long) = db.withTransaction {
        if (dao.loanCountForPerson(id) > 0) throw FinanceException("Delete this person's entries first")
        dao.deletePerson(id)
    }
}
