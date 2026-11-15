package com.mymoney.app.domain

import com.mymoney.app.core.Money
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType

/** Pure financial validation rules. Returns a user-facing error, or null when valid. */
object TransactionRules {

    fun validate(
        type: TxnType,
        amountMinor: Long,
        from: AccountEntity?,
        to: AccountEntity?,
        category: CategoryEntity?,
        isNew: Boolean,
        previousAccountIds: Set<Long> = emptySet(),
    ): String? {
        if (type.isLoan) return "Loan entries are managed from Money with People"
        if (amountMinor <= 0) return "Amount must be greater than zero"
        if (amountMinor > Money.MAX_MINOR) return "Amount is too large"
        if (from == null) return "Select an account"
        if (from.archived && (isNew || from.id !in previousAccountIds)) return "${from.name} is archived"
        when (type) {
            TxnType.INCOME, TxnType.EXPENSE -> {
                if (to != null) return "Only transfers can have a destination account"
                if (category == null) return "Select a category"
                val expected = if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                if (category.type != expected) return "Category doesn't match the transaction type"
                if (category.archived && isNew) return "Category ${category.name} is archived"
            }
            TxnType.TRANSFER -> {
                if (to == null) return "Select the destination account"
                if (to.id == from.id) return "Source and destination must be different accounts"
                if (from.type == AccountType.CREDIT_CARD) {
                    return "A credit card can't be a transfer source. Record card purchases as expenses."
                }
                if (to.archived && (isNew || to.id !in previousAccountIds)) return "${to.name} is archived"
                if (from.currency != to.currency) return "Transfers between different currencies aren't supported"
                if (category != null) return "Transfers don't use a category"
            }
            else -> return "Unsupported transaction type"
        }
        return null
    }

    /** Effect of a transaction on a given account balance (signed minor units). */
    fun effectOn(accountId: Long, type: TxnType, amountMinor: Long, fromId: Long, toId: Long?): Long {
        var e = 0L
        if (fromId == accountId) e += type.sourceSign * amountMinor
        if (type == TxnType.TRANSFER && toId == accountId) e += amountMinor
        return e
    }

    fun normaliseTags(raw: String): String =
        raw.split(',', '#').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    fun isTransferToCard(type: TxnType, to: AccountType?) = type == TxnType.TRANSFER && to == AccountType.CREDIT_CARD
}
