package com.mymoney.app.data.db

import androidx.room.Embedded

data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    /** Signed balance. For credit cards, outstanding = -balanceMinor. */
    val balanceMinor: Long,
) {
    val outstandingMinor: Long get() = if (account.type == AccountType.CREDIT_CARD) -balanceMinor else 0
}

data class TransactionDetail(
    @Embedded val txn: TransactionEntity,
    val accountName: String,
    val accountType: AccountType,
    val toAccountName: String?,
    val toAccountType: AccountType?,
    val categoryName: String?,
    val categoryIconKey: String?,
    val categoryColor: Long?,
    val personName: String?,
)

data class ScheduleDetail(
    @Embedded val schedule: ScheduledPaymentEntity,
    val accountName: String,
    val toAccountName: String?,
    val expenseCategoryName: String?,
)

data class OccurrenceDetail(
    @Embedded val occurrence: ScheduledPaymentOccurrenceEntity,
    val transactionDateMillis: Long?,
    val accountName: String?,
)

data class LoanDetail(
    @Embedded val loan: LoanEntity,
    val personName: String,
    val personPhone: String?,
    val accountName: String,
    val repaidMinor: Long,
) {
    val outstandingMinor: Long get() = if (loan.settled) 0 else (loan.amountMinor - repaidMinor).coerceAtLeast(0)
}

data class RepaymentDetail(
    @Embedded val repayment: LoanRepaymentEntity,
    val accountName: String,
)

data class PersonSummary(
    val personId: Long,
    val name: String,
    val phone: String?,
    val lentOutstandingMinor: Long,
    val borrowedOutstandingMinor: Long,
    val openCount: Int,
)

data class BudgetWithSpent(
    @Embedded val budget: BudgetEntity,
    val categoryName: String,
    val categoryIconKey: String,
    val categoryColor: Long,
    val spentMinor: Long,
)

data class GoalWithSaved(
    @Embedded val goal: SavingsGoalEntity,
    val savedMinor: Long,
    val linkedAccountName: String?,
)

data class CategoryUsage(val categoryId: Long, val count: Int)
