package com.mymoney.app.data.repo

import androidx.room.withTransaction
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.domain.TransactionRules
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class TxnInput(
    val id: Long? = null,
    val type: TxnType,
    val amountMinor: Long,
    val accountId: Long?,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val dateMillis: Long,
    val merchant: String? = null,
    val note: String? = null,
    val tags: String = "",
    /** When set on a new expense/transfer, a recurring schedule is created starting one period later. */
    val recurring: Frequency? = null,
)

data class TxnFilter(
    val range: DateRange,
    val type: TxnType? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val query: String = "",
)

enum class TxnSort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), HIGHEST("Highest amount"), LOWEST("Lowest amount") }

@Singleton
class TransactionRepository @Inject constructor(
    private val db: AppDatabase,
    private val schedules: ScheduleRepository,
    private val budgetAlerts: BudgetAlertChecker,
) {
    private val dao = db.transactionDao()

    fun observeFiltered(f: TxnFilter): Flow<List<TransactionDetail>> = dao.observeFiltered(
        f.range.startMillis, f.range.endMillisExclusive, f.type?.name, f.accountId, f.categoryId,
        f.minAmountMinor, f.maxAmountMinor, f.query.trim(),
    )

    fun observeDetail(id: Long) = dao.observeDetail(id)
    fun observeRecent(limit: Int) = dao.observeRecent(limit)
    fun observeForAccount(accountId: Long) = dao.observeForAccount(accountId)
    fun observeInRange(range: DateRange) = dao.observeDetailsInRange(range.startMillis, range.endMillisExclusive)
    suspend fun detailsInRange(range: DateRange) = dao.getDetailsInRange(range.startMillis, range.endMillisExclusive)
    fun observeSum(type: TxnType, range: DateRange) = dao.observeSum(type.name, range.startMillis, range.endMillisExclusive)
    suspend fun get(id: Long) = dao.getById(id)

    /**
     * Creates or updates an income / expense / transfer. Balances are derived from the ledger, so
     * updating the row both reverses the old effect and applies the new one atomically.
     */
    suspend fun save(input: TxnInput): Long {
        val id = db.withTransaction {
            val existing = input.id?.let { dao.getById(it) ?: throw FinanceException("Transaction not found") }
            if (existing != null && (existing.type.isLoan || existing.loanId != null)) {
                throw FinanceException("This entry is managed from Money with People")
            }
            val from = input.accountId?.let { db.accountDao().getById(it) }
            val to = input.toAccountId?.let { db.accountDao().getById(it) }
            val category = input.categoryId?.let { db.categoryDao().getById(it) }
            val prev = setOfNotNull(existing?.accountId, existing?.toAccountId)
            TransactionRules.validate(input.type, input.amountMinor, from, to, category, existing == null, prev)?.let { throw FinanceException(it) }
            val now = Dates.nowMillis()
            val clean = { s: String? -> s?.trim()?.takeIf { it.isNotEmpty() } }
            val entity = TransactionEntity(
                id = existing?.id ?: 0, type = input.type, amountMinor = input.amountMinor, accountId = from!!.id,
                toAccountId = if (input.type == TxnType.TRANSFER) to!!.id else null,
                categoryId = if (input.type == TxnType.TRANSFER) null else category!!.id,
                dateMillis = input.dateMillis, merchant = clean(input.merchant), note = clean(input.note),
                tags = TransactionRules.normaliseTags(input.tags), scheduleId = existing?.scheduleId,
                createdAt = existing?.createdAt ?: now, updatedAt = now,
            )
            if (existing == null) {
                val newId = dao.insert(entity)
                if (input.recurring != null && input.type != TxnType.INCOME) {
                    schedules.createFromTransaction(entity.copy(id = newId), input.recurring, to?.type)
                }
                newId
            } else {
                dao.update(entity)
                db.scheduleDao().getOccurrenceForTransaction(existing.id)?.let {
                    db.scheduleDao().updateOccurrence(it.copy(amountMinor = entity.amountMinor))
                }
                existing.id
            }
        }
        if (input.type == TxnType.EXPENSE) budgetAlerts.check(input.categoryId, input.dateMillis)
        return id
    }

    /**
     * Deletes a transaction. If it paid a scheduled installment, that installment becomes unpaid again
     * so the schedule never silently loses a payment.
     */
    suspend fun delete(id: Long) = db.withTransaction {
        val t = dao.getById(id) ?: throw FinanceException("Transaction not found")
        if (t.type.isLoan || t.loanId != null) throw FinanceException("This entry is managed from Money with People")
        val occ = db.scheduleDao().getOccurrenceForTransaction(id)
        dao.deleteById(id) // occurrence row cascades
        if (occ != null) schedules.reopenInstallment(occ.scheduleId, occ.dueEpochDay)
    }
}
