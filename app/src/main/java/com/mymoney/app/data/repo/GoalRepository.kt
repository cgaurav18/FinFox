package com.mymoney.app.data.repo

import androidx.room.withTransaction
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.SavingsContributionEntity
import com.mymoney.app.data.db.SavingsGoalEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class GoalInput(
    val id: Long? = null,
    val name: String,
    val targetMinor: Long,
    val targetDate: LocalDate? = null,
    /** Only applied when creating a goal; recorded as the first contribution. */
    val initialSavedMinor: Long = 0,
    val linkedAccountId: Long? = null,
    val colorArgb: Long,
)

/**
 * Savings goals earmark money already sitting in accounts; contributions never move money, so
 * nothing is double counted. Earmarks for a linked account can't exceed that account's balance.
 */
@Singleton
class GoalRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.goalDao()

    fun observeGoals(archived: Boolean) = dao.observeGoals(archived)
    fun observeGoal(id: Long) = dao.observeGoal(id)
    fun observeContributions(goalId: Long) = dao.observeContributions(goalId)
    suspend fun get(id: Long) = dao.getById(id)

    private suspend fun checkEarmark(goalId: Long, linkedAccountId: Long?, newSavedForGoal: Long) {
        if (linkedAccountId == null) return
        val acc = db.accountDao().getWithBalance(linkedAccountId) ?: return
        val others = dao.earmarkedForAccount(linkedAccountId, goalId)
        val free = acc.balanceMinor - others
        if (newSavedForGoal > free) {
            throw FinanceException("Only ${Money.format(free.coerceAtLeast(0))} in ${acc.account.name} isn't already set aside for goals")
        }
    }

    suspend fun save(i: GoalInput): Long = db.withTransaction {
        if (i.name.isBlank()) throw FinanceException("Enter a goal name")
        if (i.targetMinor <= 0) throw FinanceException("Target must be greater than zero")
        if (i.targetMinor > Money.MAX_MINOR) throw FinanceException("Amount is too large")
        if (i.initialSavedMinor < 0) throw FinanceException("Saved amount can't be negative")
        if (i.linkedAccountId != null) {
            val acc = db.accountDao().getById(i.linkedAccountId) ?: throw FinanceException("Account not found")
            if (acc.type == AccountType.CREDIT_CARD) throw FinanceException("Link a bank or cash account")
        }
        val now = Dates.nowMillis()
        if (i.id == null) {
            val id = dao.insert(SavingsGoalEntity(name = i.name.trim(), targetMinor = i.targetMinor, targetEpochDay = i.targetDate?.toEpochDay(), linkedAccountId = i.linkedAccountId, colorArgb = i.colorArgb, createdAt = now, updatedAt = now))
            if (i.initialSavedMinor > 0) {
                checkEarmark(id, i.linkedAccountId, i.initialSavedMinor)
                dao.insertContribution(SavingsContributionEntity(goalId = id, amountMinor = i.initialSavedMinor, dateMillis = now, note = "Initial amount", createdAt = now))
            }
            id
        } else {
            val old = dao.getById(i.id) ?: throw FinanceException("Goal not found")
            checkEarmark(old.id, i.linkedAccountId, dao.savedFor(old.id))
            dao.update(old.copy(name = i.name.trim(), targetMinor = i.targetMinor, targetEpochDay = i.targetDate?.toEpochDay(), linkedAccountId = i.linkedAccountId, colorArgb = i.colorArgb, updatedAt = now))
            old.id
        }
    }

    suspend fun contribute(goalId: Long, amountMinor: Long, dateMillis: Long, note: String?) = db.withTransaction {
        val g = dao.getById(goalId) ?: throw FinanceException("Goal not found")
        if (g.archived) throw FinanceException("Goal is archived")
        if (amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        checkEarmark(goalId, g.linkedAccountId, dao.savedFor(goalId) + amountMinor)
        dao.insertContribution(SavingsContributionEntity(goalId = goalId, amountMinor = amountMinor, dateMillis = dateMillis, note = note?.trim()?.takeIf { it.isNotEmpty() }, createdAt = Dates.nowMillis()))
    }

    suspend fun withdraw(goalId: Long, amountMinor: Long, dateMillis: Long, note: String?) = db.withTransaction {
        val g = dao.getById(goalId) ?: throw FinanceException("Goal not found")
        if (amountMinor <= 0) throw FinanceException("Amount must be greater than zero")
        val saved = dao.savedFor(g.id)
        if (amountMinor > saved) throw FinanceException("You can withdraw at most ${Money.format(saved)}")
        dao.insertContribution(SavingsContributionEntity(goalId = goalId, amountMinor = -amountMinor, dateMillis = dateMillis, note = note?.trim()?.takeIf { it.isNotEmpty() } ?: "Withdrawal", createdAt = Dates.nowMillis()))
    }

    suspend fun deleteContribution(id: Long) = db.withTransaction {
        val c = dao.getContribution(id) ?: throw FinanceException("Entry not found")
        val after = dao.savedFor(c.goalId) - c.amountMinor
        if (after < 0) throw FinanceException("Removing this would make the saved amount negative")
        if (c.amountMinor < 0) dao.getById(c.goalId)?.let { checkEarmark(it.id, it.linkedAccountId, after) }
        dao.deleteContribution(id)
    }

    suspend fun setArchived(id: Long, archived: Boolean) = db.withTransaction {
        val g = dao.getById(id) ?: throw FinanceException("Goal not found")
        dao.update(g.copy(archived = archived, updatedAt = Dates.nowMillis()))
    }

    suspend fun delete(id: Long) = db.withTransaction { dao.deleteById(id) }
}
