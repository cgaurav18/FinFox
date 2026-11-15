package com.mymoney.app.domain

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import org.junit.Test

class TransactionRulesTest {
    private fun acc(id: Long, type: AccountType, archived: Boolean = false, currency: String = "INR") =
        AccountEntity(id = id, type = type, name = "A$id", openingBalanceMinor = 0, colorArgb = 0, iconKey = "bank", archived = archived, currency = currency, createdAt = 0, updatedAt = 0)
    private val food = CategoryEntity(id = 1, name = "Food", type = CategoryType.EXPENSE, iconKey = "food", colorArgb = 0)
    private val salary = CategoryEntity(id = 2, name = "Salary", type = CategoryType.INCOME, iconKey = "salary", colorArgb = 0)
    private val bank = acc(1, AccountType.BANK)
    private val card = acc(2, AccountType.CREDIT_CARD)
    private val cash = acc(3, AccountType.CASH)

    @Test fun validExpenseIncomeTransfer() {
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, bank, null, food, true)).isNull()
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, card, null, food, true)).isNull()
        assertThat(TransactionRules.validate(TxnType.INCOME, 100, bank, null, salary, true)).isNull()
        assertThat(TransactionRules.validate(TxnType.TRANSFER, 100, bank, card, null, true)).isNull()
        assertThat(TransactionRules.validate(TxnType.TRANSFER, 100, bank, cash, null, true)).isNull()
    }

    @Test fun rejectsInvalidAmountsAndCombinations() {
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 0, bank, null, food, true)).isNotNull()
        assertThat(TransactionRules.validate(TxnType.EXPENSE, -5, bank, null, food, true)).isNotNull()
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, bank, null, salary, true)).contains("doesn't match")
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, null, null, food, true)).isEqualTo("Select an account")
        assertThat(TransactionRules.validate(TxnType.TRANSFER, 100, bank, bank, null, true)).contains("different")
        assertThat(TransactionRules.validate(TxnType.TRANSFER, 100, card, bank, null, true)).contains("credit card")
        assertThat(TransactionRules.validate(TxnType.TRANSFER, 100, bank, acc(9, AccountType.BANK, currency = "USD"), null, true)).contains("currencies")
        assertThat(TransactionRules.validate(TxnType.LOAN_GIVEN, 100, bank, null, null, true)).contains("Money with People")
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, acc(5, AccountType.BANK, archived = true), null, food, true)).contains("archived")
        // Editing an existing txn on an account that was archived later is still allowed.
        assertThat(TransactionRules.validate(TxnType.EXPENSE, 100, acc(5, AccountType.BANK, archived = true), null, food, false, setOf(5L))).isNull()
    }

    @Test fun effectsOnBalances() {
        assertThat(TransactionRules.effectOn(1, TxnType.EXPENSE, 500, 1, null)).isEqualTo(-500)
        assertThat(TransactionRules.effectOn(1, TxnType.INCOME, 500, 1, null)).isEqualTo(500)
        assertThat(TransactionRules.effectOn(1, TxnType.TRANSFER, 500, 1, 2)).isEqualTo(-500)
        assertThat(TransactionRules.effectOn(2, TxnType.TRANSFER, 500, 1, 2)).isEqualTo(500)
        assertThat(TransactionRules.effectOn(1, TxnType.LOAN_GIVEN, 500, 1, null)).isEqualTo(-500)
        assertThat(TransactionRules.effectOn(1, TxnType.LOAN_REPAYMENT_RECEIVED, 500, 1, null)).isEqualTo(500)
    }

    @Test fun normalisesTags() {
        assertThat(TransactionRules.normaliseTags(" Work, #Trip,work ,, ")).isEqualTo("work,trip")
    }
}
