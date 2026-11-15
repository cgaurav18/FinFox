package com.mymoney.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class AccountType { BANK, CREDIT_CARD, CASH }

enum class TxnType {
    INCOME, EXPENSE, TRANSFER,
    /** I lent money: account decreases, receivable increases. Not an expense. */
    LOAN_GIVEN,
    /** I borrowed money: account increases, payable increases. Not income. */
    LOAN_TAKEN,
    /** Someone repaid me: account increases, receivable decreases. */
    LOAN_REPAYMENT_RECEIVED,
    /** I repaid someone: account decreases, payable decreases. */
    LOAN_REPAYMENT_PAID;

    /** Sign applied to the source account ([TransactionEntity.accountId]). */
    val sourceSign: Int
        get() = when (this) {
            INCOME, LOAN_TAKEN, LOAN_REPAYMENT_RECEIVED -> 1
            EXPENSE, TRANSFER, LOAN_GIVEN, LOAN_REPAYMENT_PAID -> -1
        }
    val isLoan: Boolean get() = this in setOf(LOAN_GIVEN, LOAN_TAKEN, LOAN_REPAYMENT_RECEIVED, LOAN_REPAYMENT_PAID)
}

enum class CategoryType { EXPENSE, INCOME }

enum class Frequency(val label: String) { DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), QUARTERLY("Quarterly"), YEARLY("Yearly") }

enum class ScheduleCategory(val label: String, val iconKey: String) {
    EMI("EMI", "emi"), SIP("SIP", "sip"), ELECTRICITY("Electricity bill", "electricity"),
    INTERNET("Internet bill", "internet"), MOBILE("Mobile recharge", "mobile"), RENT("Rent", "rent"),
    INSURANCE("Insurance", "insurance"), CREDIT_CARD_BILL("Credit card bill", "credit_card"),
    SUBSCRIPTION("Subscription", "subscription"), CUSTOM("Other", "other")
}

enum class ScheduleStatus { ACTIVE, PAUSED, COMPLETED }
enum class OccurrenceStatus { PAID, SKIPPED }
enum class LoanDirection { GAVE, RECEIVED }
enum class ReminderRefType { SCHEDULE, LOAN, BUDGET, GOAL, CARD }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
@Entity(tableName = "accounts", indices = [Index("type"), Index("archived")])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AccountType,
    /** Custom account name / card name / wallet name. */
    val name: String,
    val bankName: String? = null,
    val last4: String? = null,
    /** Savings / Current / Salary / NRE … (bank only). */
    val bankAccountType: String? = null,
    /**
     * Signed opening balance in minor units. Credit cards store the opening outstanding as a negative
     * number, so every account balance is "money I hold"; card outstanding = -balance.
     */
    val openingBalanceMinor: Long,
    val currency: String = "INR",
    val colorArgb: Long,
    val iconKey: String,
    val creditLimitMinor: Long? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val description: String? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Audit trail for opening-balance changes. */
@Serializable
@Entity(
    tableName = "balance_adjustments",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId")],
)
data class BalanceAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val oldOpeningMinor: Long,
    val newOpeningMinor: Long,
    val reason: String?,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "categories", indices = [Index(value = ["name", "type"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: CategoryType,
    val iconKey: String,
    val colorArgb: Long,
    val isSystem: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
@Entity(
    tableName = "people",
    indices = [Index("name")],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "scheduled_payments",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["expenseCategoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("accountId"), Index("toAccountId"), Index("expenseCategoryId"), Index("nextDueEpochDay"), Index("status")],
)
data class ScheduledPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: ScheduleCategory,
    val amountMinor: Long,
    val accountId: Long,
    /** For credit card bills: the card being paid. Payment is then a transfer, not an expense. */
    val toAccountId: Long? = null,
    /** Expense category used for the linked transaction. */
    val expenseCategoryId: Long? = null,
    val startEpochDay: Long,
    val endEpochDay: Long? = null,
    val frequency: Frequency,
    val nextDueEpochDay: Long,
    val reminderDaysBefore: Int = 1,
    val notes: String? = null,
    val status: ScheduleStatus = ScheduleStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "loan_ledger",
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("personId"), Index("accountId"), Index("direction"), Index("settled")],
)
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val direction: LoanDirection,
    val amountMinor: Long,
    val dateMillis: Long,
    val dueEpochDay: Long? = null,
    val accountId: Long,
    val description: String? = null,
    val reminderEnabled: Boolean = false,
    val settled: Boolean = false,
    /** Amount forgiven / written off when settled without full repayment. */
    val writtenOffMinor: Long = 0,
    val settledAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "loan_repayments",
    foreignKeys = [
        ForeignKey(entity = LoanEntity::class, parentColumns = ["id"], childColumns = ["loanId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("loanId"), Index("accountId")],
)
data class LoanRepaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val amountMinor: Long,
    val dateMillis: Long,
    val accountId: Long,
    val note: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ScheduledPaymentEntity::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = LoanEntity::class, parentColumns = ["id"], childColumns = ["loanId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LoanRepaymentEntity::class, parentColumns = ["id"], childColumns = ["repaymentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("accountId"), Index("toAccountId"), Index("categoryId"), Index("dateMillis"), Index("type"),
        Index("scheduleId"), Index("loanId"), Index("repaymentId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxnType,
    /** Always positive; direction comes from [type]. */
    val amountMinor: Long,
    val accountId: Long,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val dateMillis: Long,
    val merchant: String? = null,
    val note: String? = null,
    /** Comma separated, normalised lower-case tags. */
    val tags: String = "",
    val scheduleId: Long? = null,
    val loanId: Long? = null,
    val repaymentId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "scheduled_payment_occurrences",
    foreignKeys = [
        ForeignKey(entity = ScheduledPaymentEntity::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE),
    ],
    // One record per installment: this unique index is what prevents double payment.
    indices = [Index(value = ["scheduleId", "dueEpochDay"], unique = true), Index("transactionId")],
)
data class ScheduledPaymentOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val dueEpochDay: Long,
    val status: OccurrenceStatus,
    val transactionId: Long? = null,
    val amountMinor: Long,
    val actedAt: Long,
)

@Serializable
@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["categoryId", "yearMonth"], unique = true), Index("yearMonth")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    /** yyyyMM, e.g. 202611. */
    val yearMonth: Int,
    val limitMinor: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "savings_goals",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["linkedAccountId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("linkedAccountId"), Index("archived")],
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val targetEpochDay: Long? = null,
    val linkedAccountId: Long? = null,
    val colorArgb: Long,
    val iconKey: String = "savings",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Contributions earmark money for a goal. They never move money between accounts, so savings are not
 * double counted against account balances. Withdrawals are stored as negative amounts.
 */
@Serializable
@Entity(
    tableName = "savings_contributions",
    foreignKeys = [ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId")],
)
data class SavingsContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amountMinor: Long,
    val dateMillis: Long,
    val note: String? = null,
    val createdAt: Long,
)

/** Tracks which reminders/alerts were already posted so each fires once. */
@Serializable
@Entity(
    tableName = "notification_reminders",
    indices = [Index(value = ["refType", "refId", "dueKey"], unique = true)],
)
data class NotificationReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val refType: ReminderRefType,
    val refId: Long,
    val dueKey: Long,
    val notifiedAt: Long,
)

@Serializable
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val profileName: String = "",
    val currency: String = "INR",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val notificationsEnabled: Boolean = true,
    val budgetAlertsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "9") val reminderHour: Int = 9,
    /** Hide balances on the dashboard by default. */
    @ColumnInfo(defaultValue = "0") val hideBalances: Boolean = false,
)
