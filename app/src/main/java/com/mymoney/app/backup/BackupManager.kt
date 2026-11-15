package com.mymoney.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BalanceAdjustmentEntity
import com.mymoney.app.data.db.BudgetEntity
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.DefaultData
import com.mymoney.app.data.db.LoanEntity
import com.mymoney.app.data.db.LoanRepaymentEntity
import com.mymoney.app.data.db.NotificationReminderEntity
import com.mymoney.app.data.db.PersonEntity
import com.mymoney.app.data.db.SavingsContributionEntity
import com.mymoney.app.data.db.SavingsGoalEntity
import com.mymoney.app.data.db.ScheduledPaymentEntity
import com.mymoney.app.data.db.ScheduledPaymentOccurrenceEntity
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.db.TransactionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val schemaVersion: Int,
    val exportedAt: Long,
    val accounts: List<AccountEntity>,
    val adjustments: List<BalanceAdjustmentEntity>,
    val categories: List<CategoryEntity>,
    val people: List<PersonEntity>,
    val schedules: List<ScheduledPaymentEntity>,
    val loans: List<LoanEntity>,
    val repayments: List<LoanRepaymentEntity>,
    val transactions: List<TransactionEntity>,
    val occurrences: List<ScheduledPaymentOccurrenceEntity>,
    val budgets: List<BudgetEntity>,
    val goals: List<SavingsGoalEntity>,
    val contributions: List<SavingsContributionEntity>,
    val reminders: List<NotificationReminderEntity>,
    val settings: List<SettingsEntity>,
)

data class BackupStats(val accounts: Int, val transactions: Int)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun snapshot(): BackupPayload = db.withTransaction {
        val d = db.backupDao()
        BackupPayload(
            schemaVersion = AppDatabase.VERSION, exportedAt = System.currentTimeMillis(),
            accounts = d.accounts(), adjustments = d.adjustments(), categories = d.categories(), people = d.people(),
            schedules = d.schedules(), loans = d.loans(), repayments = d.repayments(), transactions = d.transactions(),
            occurrences = d.occurrences(), budgets = d.budgets(), goals = d.goals(), contributions = d.contributions(),
            reminders = d.reminders(), settings = d.settings(),
        )
    }

    fun encode(p: BackupPayload): ByteArray = json.encodeToString(BackupPayload.serializer(), p).toByteArray()
    fun decode(bytes: ByteArray): BackupPayload = try {
        json.decodeFromString(BackupPayload.serializer(), bytes.decodeToString())
    } catch (e: Exception) { throw BackupException("The backup contents are invalid") }

    /** Replaces all data with [p] in a single transaction: either everything is restored or nothing changes. */
    suspend fun replaceAll(p: BackupPayload) {
        if (p.schemaVersion > AppDatabase.VERSION) throw BackupException("This backup was made by a newer app version")
        db.withTransaction {
            clearInTransaction()
            val d = db.backupDao()
            d.insertCategories(p.categories); d.insertAccounts(p.accounts); d.insertAdjustments(p.adjustments)
            d.insertPeople(p.people); d.insertSchedules(p.schedules); d.insertLoans(p.loans); d.insertRepayments(p.repayments)
            d.insertTransactions(p.transactions); d.insertOccurrences(p.occurrences); d.insertBudgets(p.budgets)
            d.insertGoals(p.goals); d.insertContributions(p.contributions); d.insertReminders(p.reminders); d.insertSettings(p.settings)
        }
        DefaultData.ensureSeeded(db)
    }

    private suspend fun clearInTransaction() {
        val d = db.backupDao()
        d.clearReminders(); d.clearContributions(); d.clearGoals(); d.clearBudgets(); d.clearOccurrences()
        d.clearTransactions(); d.clearRepayments(); d.clearLoans(); d.clearSchedules(); d.clearPeople()
        d.clearAdjustments(); d.clearAccounts(); d.clearCategories(); d.clearSettings()
    }

    /** Wipes every table and re-seeds defaults. */
    suspend fun resetAll() {
        db.withTransaction { clearInTransaction() }
        DefaultData.ensureSeeded(db)
    }

    suspend fun exportTo(uri: Uri, password: CharArray): BackupStats = withContext(Dispatchers.IO) {
        val p = snapshot()
        val bytes = BackupCrypto.encrypt(encode(p), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw BackupException("Couldn't open the file")
        BackupStats(p.accounts.size, p.transactions.size)
    }

    suspend fun readFrom(uri: Uri, password: CharArray): BackupPayload = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Couldn't open the file")
        if (bytes.size > 100 * 1024 * 1024) throw BackupException("File is too large")
        decode(BackupCrypto.decrypt(bytes, password))
    }
}
