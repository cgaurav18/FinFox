package com.mymoney.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Version 3 tables: automatic capture of payments from bank SMS / UPI app notifications, and payments
 * started from FinFox's own "Scan & Pay". Local only (never backed up): they hold raw message text.
 */

enum class CaptureStatus {
    /** A transaction was created from it. */
    ADDED,
    /** Waiting for the user to confirm (review mode, unknown account, card bill payment, inbox import). */
    PENDING,
    /** The user dismissed it. */
    IGNORED,
}

@Entity(
    tableName = "captured_messages",
    foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("receivedAt"), Index("status"), Index("textHash"), Index("refNo"), Index("transactionId")],
)
data class CapturedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "sms", a notification's package name, or "finfox-pay" for Scan & Pay results. */
    val source: String,
    val sender: String?,
    val body: String,
    val textHash: String,
    val receivedAt: Long,
    /** DEBIT or CREDIT. */
    val direction: String,
    val amountMinor: Long,
    val accountLast4: String?,
    val isCard: Boolean,
    val counterparty: String?,
    val vpa: String?,
    val refNo: String?,
    /** NORMAL or CARD_BILL_PAYMENT. */
    val kind: String,
    val status: CaptureStatus,
    val suggestedAccountId: Long?,
    val suggestedCategoryId: Long?,
    val transactionId: Long?,
    val createdAt: Long,
)

/** The category the user last chose for a merchant / UPI handle. */
@Entity(
    tableName = "merchant_rules",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId")],
)
data class MerchantRuleEntity(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long,
)

enum class UpiPaymentStatus {
    /** Handed to a UPI app; outcome not known yet. */
    STARTED,
    /** The UPI app reported success and FinFox recorded the transaction. */
    SUCCESS,
    /** The UPI app reported failure, or the user said it didn't go through. */
    FAILED,
    /** A bank SMS / notification later confirmed it and the transaction was recorded. */
    MATCHED,
}

/** A payment started from Scan & Pay. Kept so a later bank SMS can be recorded with the category chosen here. */
@Entity(tableName = "upi_payments", indices = [Index("status"), Index("createdAt")])
data class UpiPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vpa: String,
    val payeeName: String?,
    val amountMinor: Long,
    val categoryId: Long?,
    val accountId: Long?,
    val note: String?,
    val txnRef: String,
    val appPackage: String?,
    val status: UpiPaymentStatus,
    val transactionId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "auto_capture_preferences")
data class AutoCapturePreferencesEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val readSms: Boolean = true,
    val readNotifications: Boolean = true,
    /** true: add straight to the ledger. false: everything waits in the review list. */
    val autoAdd: Boolean = true,
    val notifyOnCapture: Boolean = true,
    /** Used when a message doesn't name an account (most UPI app notifications). */
    val defaultAccountId: Long? = null,
)

@Dao
interface CaptureDao {
    @Insert suspend fun insert(m: CapturedMessageEntity): Long
    @Update suspend fun update(m: CapturedMessageEntity)

    @Query("SELECT * FROM captured_messages WHERE id = :id") suspend fun get(id: Long): CapturedMessageEntity?
    @Query("SELECT * FROM captured_messages WHERE transactionId = :txnId LIMIT 1") suspend fun forTransaction(txnId: Long): CapturedMessageEntity?

    /** Candidates for duplicate detection: anything not ignored around the same time. */
    @Query("SELECT * FROM captured_messages WHERE receivedAt BETWEEN :from AND :to AND status != 'IGNORED'")
    suspend fun around(from: Long, to: Long): List<CapturedMessageEntity>

    @Query("SELECT COUNT(*) FROM captured_messages WHERE textHash = :hash") suspend fun countHash(hash: String): Int
    @Query("SELECT COUNT(*) FROM captured_messages WHERE refNo = :ref AND status != 'IGNORED'") suspend fun countRef(ref: String): Int

    @Query("SELECT * FROM captured_messages WHERE status = 'PENDING' ORDER BY receivedAt DESC")
    fun observePending(): Flow<List<CapturedMessageEntity>>

    @Query("SELECT * FROM captured_messages WHERE status = 'PENDING' ORDER BY receivedAt")
    suspend fun pendingList(): List<CapturedMessageEntity>

    @Query("SELECT COUNT(*) FROM captured_messages WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM captured_messages WHERE status != 'PENDING' ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CapturedMessageEntity>>

    @Query("DELETE FROM captured_messages WHERE status != 'PENDING'") suspend fun clearHistory()
    @Query("DELETE FROM captured_messages") suspend fun clearAllCaptured()
    @Query("DELETE FROM upi_payments") suspend fun clearPayments()

    @Upsert suspend fun upsertRule(r: MerchantRuleEntity)
    @Query("SELECT * FROM merchant_rules WHERE merchantKey = :key") suspend fun rule(key: String): MerchantRuleEntity?

    @Insert suspend fun insertPayment(p: UpiPaymentEntity): Long
    @Update suspend fun updatePayment(p: UpiPaymentEntity)
    @Query("SELECT * FROM upi_payments WHERE id = :id") suspend fun payment(id: Long): UpiPaymentEntity?
    @Query("SELECT * FROM upi_payments WHERE status = 'STARTED' AND amountMinor = :amount AND createdAt >= :since ORDER BY createdAt DESC LIMIT 1")
    suspend fun openPayment(amount: Long, since: Long): UpiPaymentEntity?

    @Query("SELECT * FROM auto_capture_preferences WHERE id = 1") suspend fun getPrefs(): AutoCapturePreferencesEntity?
    @Query("SELECT * FROM auto_capture_preferences WHERE id = 1") fun observePrefs(): Flow<AutoCapturePreferencesEntity?>
    @Upsert suspend fun upsertPrefs(p: AutoCapturePreferencesEntity)

    /** Manually entered transactions of the same type and amount near this time: the user already recorded it. */
    @Query("SELECT COUNT(*) FROM transactions WHERE type = :type AND amountMinor = :amount AND dateMillis BETWEEN :from AND :to")
    suspend fun similarTransactions(type: String, amount: Long, from: Long, to: Long): Int
}
