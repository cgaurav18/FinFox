package com.mymoney.app.backup

import com.mymoney.app.data.db.AppDatabase

/**
 * Merges a backup into existing data (must run inside a Room transaction). Records are matched on
 * natural keys rather than ids — ids from another device are meaningless here — and every foreign key
 * is remapped. Anything already present is skipped, so merging the same backup twice adds nothing.
 * Existing records are never overwritten; settings and preferences on this device are kept.
 */
internal class BackupMerger(private val db: AppDatabase) {
    private val inserted = linkedMapOf<String, Int>()
    private val skipped = linkedMapOf<String, Int>()
    private fun add(m: MutableMap<String, Int>, k: String) { m[k] = (m[k] ?: 0) + 1 }

    private fun norm(s: String?) = s?.trim()?.lowercase() ?: ""

    suspend fun merge(p: BackupPayload): Pair<Map<String, Int>, Map<String, Int>> {
        val d = db.backupDao()

        // Categories: unique by (name, type).
        val catMap = HashMap<Long, Long>()
        val existingCats = d.categories().associateBy { norm(it.name) to it.type }
        p.categories.forEach { c ->
            val hit = existingCats[norm(c.name) to c.type]
            if (hit != null) { catMap[c.id] = hit.id; add(skipped, "Categories") }
            else { catMap[c.id] = db.categoryDao().insert(c.copy(id = 0)); add(inserted, "Categories") }
        }

        // Accounts: (type, name, last4, currency).
        val accMap = HashMap<Long, Long>()
        val existingAcc = d.accounts().associateBy { listOf(it.type.name, norm(it.name), it.last4 ?: "", it.currency) }
        val newAccounts = HashSet<Long>()
        p.accounts.forEach { a ->
            val hit = existingAcc[listOf(a.type.name, norm(a.name), a.last4 ?: "", a.currency)]
            if (hit != null) { accMap[a.id] = hit.id; add(skipped, "Accounts") }
            else { val id = db.accountDao().insert(a.copy(id = 0)); accMap[a.id] = id; newAccounts += id; add(inserted, "Accounts") }
        }
        // Opening-balance audit entries only belong to accounts that were newly created.
        val newAdj = p.adjustments.mapNotNull { adj -> accMap[adj.accountId]?.takeIf { it in newAccounts }?.let { adj.copy(id = 0, accountId = it) } }
        if (newAdj.isNotEmpty()) d.insertAdjustments(newAdj)

        // People: by name.
        val personMap = HashMap<Long, Long>()
        val existingPeople = d.people().associateBy { norm(it.name) }
        p.people.forEach { pe ->
            val hit = existingPeople[norm(pe.name)]
            personMap[pe.id] = hit?.id ?: db.loanDao().insertPerson(pe.copy(id = 0))
        }

        // Scheduled payments.
        val schMap = HashMap<Long, Long>()
        val existingSch = d.schedules().associateBy { listOf(norm(it.title), it.category.name, it.amountMinor, it.accountId, it.startEpochDay, it.frequency.name) }
        p.schedules.forEach { s ->
            val acc = accMap.getValue(s.accountId)
            val hit = existingSch[listOf(norm(s.title), s.category.name, s.amountMinor, acc, s.startEpochDay, s.frequency.name)]
            if (hit != null) { schMap[s.id] = hit.id; add(skipped, "Scheduled payments") }
            else {
                schMap[s.id] = db.scheduleDao().insert(s.copy(id = 0, accountId = acc, toAccountId = s.toAccountId?.let(accMap::getValue), expenseCategoryId = s.expenseCategoryId?.let(catMap::get)))
                add(inserted, "Scheduled payments")
            }
        }

        // Loans: (person, direction, amount, date).
        val loanMap = HashMap<Long, Long>()
        val existingLoans = d.loans().associateBy { listOf(it.personId, it.direction.name, it.amountMinor, it.dateMillis) }
        p.loans.forEach { l ->
            val person = personMap.getValue(l.personId)
            val hit = existingLoans[listOf(person, l.direction.name, l.amountMinor, l.dateMillis)]
            if (hit != null) { loanMap[l.id] = hit.id; add(skipped, "Lending & borrowing") }
            else { loanMap[l.id] = db.loanDao().insertLoan(l.copy(id = 0, personId = person, accountId = accMap.getValue(l.accountId))); add(inserted, "Lending & borrowing") }
        }

        // Repayments: (loan, amount, date).
        val repMap = HashMap<Long, Long>()
        val existingRep = d.repayments().associateBy { listOf(it.loanId, it.amountMinor, it.dateMillis) }
        p.repayments.forEach { r ->
            val loan = loanMap.getValue(r.loanId)
            val hit = existingRep[listOf(loan, r.amountMinor, r.dateMillis)]
            if (hit != null) { repMap[r.id] = hit.id; add(skipped, "Repayments") }
            else { repMap[r.id] = db.loanDao().insertRepayment(r.copy(id = 0, loanId = loan, accountId = accMap.getValue(r.accountId))); add(inserted, "Repayments") }
        }

        // Transactions: (type, amount, account, to-account, date, category).
        val txnMap = HashMap<Long, Long>()
        val existingTxn = d.transactions().associateBy { listOf(it.type.name, it.amountMinor, it.accountId, it.toAccountId, it.dateMillis, it.categoryId) }
        p.transactions.forEach { t ->
            val mapped = t.copy(
                id = 0, accountId = accMap.getValue(t.accountId), toAccountId = t.toAccountId?.let(accMap::getValue),
                categoryId = t.categoryId?.let(catMap::getValue), scheduleId = t.scheduleId?.let(schMap::get),
                loanId = t.loanId?.let(loanMap::getValue), repaymentId = t.repaymentId?.let(repMap::getValue),
            )
            val hit = existingTxn[listOf(mapped.type.name, mapped.amountMinor, mapped.accountId, mapped.toAccountId, mapped.dateMillis, mapped.categoryId)]
            if (hit != null) { txnMap[t.id] = hit.id; add(skipped, "Transactions") }
            else { txnMap[t.id] = db.transactionDao().insert(mapped); add(inserted, "Transactions") }
        }

        // Installments: unique per (schedule, due date).
        p.occurrences.forEach { o ->
            val sch = schMap.getValue(o.scheduleId)
            if (db.scheduleDao().getOccurrence(sch, o.dueEpochDay) == null) {
                db.scheduleDao().insertOccurrence(o.copy(id = 0, scheduleId = sch, transactionId = o.transactionId?.let(txnMap::get)))
            }
        }

        // Budgets: unique per (category, month).
        p.budgets.forEach { b ->
            val cat = catMap.getValue(b.categoryId)
            if (db.budgetDao().getFor(cat, b.yearMonth) == null) { db.budgetDao().insert(b.copy(id = 0, categoryId = cat)); add(inserted, "Budgets") }
            else add(skipped, "Budgets")
        }

        // Goals by name; contributions by (goal, amount, date).
        val goalMap = HashMap<Long, Long>()
        val existingGoals = d.goals().associateBy { norm(it.name) }
        p.goals.forEach { g ->
            val hit = existingGoals[norm(g.name)]
            if (hit != null) { goalMap[g.id] = hit.id; add(skipped, "Savings goals") }
            else { goalMap[g.id] = db.goalDao().insert(g.copy(id = 0, linkedAccountId = g.linkedAccountId?.let(accMap::get))); add(inserted, "Savings goals") }
        }
        val existingContrib = d.contributions().map { listOf(it.goalId, it.amountMinor, it.dateMillis) }.toHashSet()
        p.contributions.forEach { c ->
            val goal = goalMap.getValue(c.goalId)
            if (listOf(goal, c.amountMinor, c.dateMillis) !in existingContrib) db.goalDao().insertContribution(c.copy(id = 0, goalId = goal))
        }
        return inserted to skipped
    }
}
