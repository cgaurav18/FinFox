package com.mymoney.app.data.repo

import androidx.room.withTransaction
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AccountWithBalance
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BalanceAdjustmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AccountInput(
    val id: Long? = null,
    val type: AccountType,
    val name: String,
    val bankName: String? = null,
    val last4: String? = null,
    val bankAccountType: String? = null,
    /** Bank/cash: opening balance. Credit card: opening outstanding (positive = owed). */
    val openingMinor: Long,
    val currency: String = "INR",
    val colorArgb: Long,
    val iconKey: String,
    val creditLimitMinor: Long? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val description: String? = null,
    val adjustmentReason: String? = null,
)

data class BalanceTotals(
    val bankMinor: Long,
    val cashMinor: Long,
    val creditOutstandingMinor: Long,
    val creditLimitMinor: Long,
) {
    /** Available = bank + cash. Unused credit limit is never counted as money. */
    val availableMinor: Long get() = bankMinor + cashMinor
}

@Singleton
class AccountRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.accountDao()

    fun observeAccounts(includeArchived: Boolean = false): Flow<List<AccountWithBalance>> = dao.observeWithBalance(includeArchived)
    fun observeAccount(id: Long): Flow<AccountWithBalance?> = dao.observeWithBalance(id)
    fun observeAdjustments(id: Long) = dao.observeAdjustments(id)
    suspend fun get(id: Long) = dao.getById(id)

    fun observeTotals(): Flow<BalanceTotals> = dao.observeWithBalance(false).map(::totals)

    companion object {
        fun totals(list: List<AccountWithBalance>) = BalanceTotals(
            bankMinor = list.filter { it.account.type == AccountType.BANK }.sumOf { it.balanceMinor },
            cashMinor = list.filter { it.account.type == AccountType.CASH }.sumOf { it.balanceMinor },
            creditOutstandingMinor = list.filter { it.account.type == AccountType.CREDIT_CARD }.sumOf { it.outstandingMinor },
            creditLimitMinor = list.filter { it.account.type == AccountType.CREDIT_CARD }.sumOf { it.account.creditLimitMinor ?: 0 },
        )
    }

    private fun validate(i: AccountInput) {
        if (i.name.isBlank()) throw FinanceException("Enter a name")
        if (i.name.length > 60) throw FinanceException("Name is too long")
        if (!i.last4.isNullOrBlank() && !Regex("^\\d{4}$").matches(i.last4)) throw FinanceException("Last 4 digits must be exactly 4 numbers")
        if (i.openingMinor < 0 && i.type != AccountType.BANK) throw FinanceException("Opening amount can't be negative")
        if (kotlin.math.abs(i.openingMinor) > Money.MAX_MINOR) throw FinanceException("Amount is too large")
        if (i.currency !in Money.supportedCurrencies) throw FinanceException("Unsupported currency")
        if (i.type == AccountType.CREDIT_CARD) {
            if (i.bankName.isNullOrBlank()) throw FinanceException("Enter the card's bank name")
            val limit = i.creditLimitMinor ?: throw FinanceException("Enter the credit limit")
            if (limit <= 0) throw FinanceException("Credit limit must be greater than zero")
            if (i.statementDay != null && i.statementDay !in 1..31) throw FinanceException("Statement day must be 1-31")
            if (i.dueDay != null && i.dueDay !in 1..31) throw FinanceException("Due day must be 1-31")
        }
        if (i.type == AccountType.BANK && i.bankName.isNullOrBlank()) throw FinanceException("Enter the bank name")
    }

    private fun signedOpening(i: AccountInput) = if (i.type == AccountType.CREDIT_CARD) -i.openingMinor else i.openingMinor

    /** Creates or updates an account. Opening-balance changes on existing accounts are written to the audit trail. */
    suspend fun save(i: AccountInput): Long = db.withTransaction {
        validate(i)
        val now = Dates.nowMillis()
        val clean = { s: String? -> s?.trim()?.takeIf { it.isNotEmpty() } }
        if (i.id == null) {
            dao.insert(
                AccountEntity(
                    type = i.type, name = i.name.trim(), bankName = clean(i.bankName), last4 = clean(i.last4),
                    bankAccountType = clean(i.bankAccountType), openingBalanceMinor = signedOpening(i), currency = i.currency,
                    colorArgb = i.colorArgb, iconKey = i.iconKey, creditLimitMinor = i.creditLimitMinor.takeIf { i.type == AccountType.CREDIT_CARD },
                    statementDay = i.statementDay, dueDay = i.dueDay, description = clean(i.description), createdAt = now, updatedAt = now,
                )
            )
        } else {
            val old = dao.getById(i.id) ?: throw FinanceException("Account not found")
            if (old.type != i.type) throw FinanceException("Account type can't be changed")
            val newOpening = signedOpening(i)
            if (newOpening != old.openingBalanceMinor) {
                dao.insertAdjustment(BalanceAdjustmentEntity(accountId = old.id, oldOpeningMinor = old.openingBalanceMinor, newOpeningMinor = newOpening, reason = clean(i.adjustmentReason) ?: "Edited account", createdAt = now))
            }
            dao.update(
                old.copy(
                    name = i.name.trim(), bankName = clean(i.bankName), last4 = clean(i.last4), bankAccountType = clean(i.bankAccountType),
                    openingBalanceMinor = newOpening, currency = i.currency, colorArgb = i.colorArgb, iconKey = i.iconKey,
                    creditLimitMinor = i.creditLimitMinor.takeIf { i.type == AccountType.CREDIT_CARD }, statementDay = i.statementDay,
                    dueDay = i.dueDay, description = clean(i.description), updatedAt = now,
                )
            )
            old.id
        }
    }

    /** Sets the balance shown today to [targetBalanceMinor] by adjusting the opening balance (audited). */
    suspend fun reconcileBalance(accountId: Long, targetBalanceMinor: Long, reason: String?) = db.withTransaction {
        val awb = dao.getWithBalance(accountId) ?: throw FinanceException("Account not found")
        val delta = targetBalanceMinor - awb.balanceMinor
        if (delta == 0L) return@withTransaction
        val old = awb.account
        val newOpening = old.openingBalanceMinor + delta
        dao.insertAdjustment(BalanceAdjustmentEntity(accountId = old.id, oldOpeningMinor = old.openingBalanceMinor, newOpeningMinor = newOpening, reason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "Balance reconciliation", createdAt = Dates.nowMillis()))
        dao.update(old.copy(openingBalanceMinor = newOpening, updatedAt = Dates.nowMillis()))
    }

    suspend fun setArchived(id: Long, archived: Boolean) = db.withTransaction {
        val a = dao.getById(id) ?: throw FinanceException("Account not found")
        dao.update(a.copy(archived = archived, updatedAt = Dates.nowMillis()))
    }

    /** Deletion is only allowed for accounts without history. Otherwise the user must archive. */
    suspend fun delete(id: Long) = db.withTransaction {
        val a = dao.getById(id) ?: throw FinanceException("Account not found")
        val refs = dao.referenceCount(id)
        if (refs > 0) throw FinanceException("${a.name} has $refs linked record(s). Archive it instead to keep your history.")
        dao.delete(a)
    }

    suspend fun referenceCount(id: Long) = dao.referenceCount(id)
}
