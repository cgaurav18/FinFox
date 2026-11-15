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
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.NotificationReminderEntity
import com.mymoney.app.data.db.PersonEntity
import com.mymoney.app.data.db.SavingsContributionEntity
import com.mymoney.app.data.db.SavingsGoalEntity
import com.mymoney.app.data.db.ScheduledPaymentEntity
import com.mymoney.app.data.db.ScheduledPaymentOccurrenceEntity
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.WidgetPreferencesEntity
import com.mymoney.app.security.SecretBox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versioned backup contents. Contains financial records and app preferences only — never PINs,
 * biometric settings, Google identity/tokens, backup keys, notification history or caches.
 */
@Serializable
data class BackupPayload(
    val formatVersion: Int = CURRENT_FORMAT,
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
    // Added in format 2
    val notificationPreferences: List<NotificationPreferencesEntity> = emptyList(),
    val widgetPreferences: List<WidgetPreferencesEntity> = emptyList(),
) {
    companion object { const val CURRENT_FORMAT = 2 }
}

data class BackupStats(val accounts: Int, val transactions: Int)

enum class RestoreMode { REPLACE, MERGE }

data class RestoreReport(
    val mode: RestoreMode,
    /** Rows added per table (MERGE) or restored (REPLACE). */
    val inserted: Map<String, Int>,
    /** Rows recognised as already present and skipped (MERGE only). */
    val duplicatesSkipped: Map<String, Int>,
    val accountsAfter: Int,
    val availableBalanceMinor: Long,
) {
    val totalInserted get() = inserted.values.sum()
    val totalSkipped get() = duplicatesSkipped.values.sum()
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val box: SecretBox,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val safetyFile get() = File(context.filesDir, "restore_safety.bin")

    /** A consistent snapshot: all tables are read inside one transaction. */
    suspend fun snapshot(): BackupPayload = db.withTransaction {
        val d = db.backupDao()
        BackupPayload(
            schemaVersion = AppDatabase.VERSION, exportedAt = System.currentTimeMillis(),
            accounts = d.accounts(), adjustments = d.adjustments(), categories = d.categories(), people = d.people(),
            schedules = d.schedules(), loans = d.loans(), repayments = d.repayments(), transactions = d.transactions(),
            occurrences = d.occurrences(), budgets = d.budgets(), goals = d.goals(), contributions = d.contributions(),
            reminders = d.reminders(), settings = d.settings(),
            notificationPreferences = listOfNotNull(db.notificationDao().getPrefs()),
            widgetPreferences = listOfNotNull(db.widgetDao().getPrefs()),
        )
    }

    fun encode(p: BackupPayload): ByteArray = json.encodeToString(BackupPayload.serializer(), p).toByteArray()
    fun decode(bytes: ByteArray): BackupPayload {
        val p = try {
            json.decodeFromString(BackupPayload.serializer(), bytes.decodeToString())
        } catch (e: Exception) { throw BackupException("The backup contents are invalid or corrupted") }
        validate(p)
        return p
    }

    /** Schema/version checks plus referential integrity, before anything touches the database. */
    fun validate(p: BackupPayload) {
        if (p.formatVersion !in 1..BackupPayload.CURRENT_FORMAT) throw BackupException("This backup was made by a newer version of My Money")
        if (p.schemaVersion > AppDatabase.VERSION) throw BackupException("This backup was made by a newer version of My Money")
        if (p.schemaVersion < 1) throw BackupException("The backup has an invalid schema version")
        val problems = mutableListOf<String>()
        fun <T> unique(name: String, list: List<T>, id: (T) -> Long) { if (list.map(id).toSet().size != list.size) problems += "duplicate $name ids" }
        unique("account", p.accounts) { it.id }; unique("category", p.categories) { it.id }; unique("person", p.people) { it.id }
        unique("transaction", p.transactions) { it.id }; unique("schedule", p.schedules) { it.id }; unique("loan", p.loans) { it.id }
        unique("repayment", p.repayments) { it.id }; unique("goal", p.goals) { it.id }; unique("budget", p.budgets) { it.id }
        val acc = p.accounts.map { it.id }.toSet(); val cat = p.categories.map { it.id }.toSet()
        val sch = p.schedules.map { it.id }.toSet(); val loan = p.loans.map { it.id }.toSet(); val rep = p.repayments.map { it.id }.toSet()
        val ppl = p.people.map { it.id }.toSet(); val goal = p.goals.map { it.id }.toSet(); val txn = p.transactions.map { it.id }.toSet()
        p.transactions.forEach { t ->
            if (t.amountMinor <= 0) problems += "transaction ${t.id} has an invalid amount"
            if (t.accountId !in acc || (t.toAccountId != null && t.toAccountId !in acc)) problems += "transaction ${t.id} references a missing account"
            if (t.categoryId != null && t.categoryId !in cat) problems += "transaction ${t.id} references a missing category"
            if (t.loanId != null && t.loanId !in loan) problems += "transaction ${t.id} references a missing loan"
            if (t.repaymentId != null && t.repaymentId !in rep) problems += "transaction ${t.id} references a missing repayment"
        }
        p.schedules.forEach { if (it.accountId !in acc || (it.toAccountId != null && it.toAccountId !in acc)) problems += "schedule ${it.id} references a missing account" }
        p.loans.forEach { if (it.personId !in ppl || it.accountId !in acc) problems += "loan ${it.id} references missing data" }
        p.repayments.forEach { if (it.loanId !in loan || it.accountId !in acc) problems += "repayment ${it.id} references missing data" }
        p.occurrences.forEach { if (it.scheduleId !in sch || (it.transactionId != null && it.transactionId !in txn)) problems += "installment ${it.id} references missing data" }
        p.budgets.forEach { if (it.categoryId !in cat) problems += "budget ${it.id} references a missing category" }
        p.contributions.forEach { if (it.goalId !in goal) problems += "goal contribution ${it.id} references a missing goal" }
        p.adjustments.forEach { if (it.accountId !in acc) problems += "balance adjustment ${it.id} references a missing account" }
        if (problems.isNotEmpty()) throw BackupException("The backup is corrupted: " + problems.take(3).joinToString("; ") + if (problems.size > 3) " (+${problems.size - 3} more)" else "")
    }

    /**
     * Restores [p]. A sealed safety copy of the current data is written first so the restore can be
     * undone; the restore itself runs in one transaction and is verified with a foreign-key check, so a
     * failure rolls back completely and leaves current data untouched.
     */
    suspend fun restore(p: BackupPayload, mode: RestoreMode, keepSafetyCopy: Boolean = true): RestoreReport {
        validate(p)
        if (keepSafetyCopy) writeSafetyCopy()
        val (inserted, skipped) = db.withTransaction {
            val result = when (mode) {
                RestoreMode.REPLACE -> { replaceInTransaction(p); counts(p) to emptyMap() }
                RestoreMode.MERGE -> BackupMerger(db).merge(p)
            }
            verifyIntegrityInTransaction()
            result
        }
        DefaultData.ensureSeeded(db)
        val accounts = db.accountDao().getAllWithBalance().filter { !it.account.archived }
        val available = accounts.filter { it.account.type != com.mymoney.app.data.db.AccountType.CREDIT_CARD }.sumOf { it.balanceMinor }
        return RestoreReport(mode, inserted, skipped, accounts.size, available)
    }

    /** Legacy entry point used by tests and the file restore: replace everything. */
    suspend fun replaceAll(p: BackupPayload) { restore(p, RestoreMode.REPLACE, keepSafetyCopy = false) }

    private fun counts(p: BackupPayload) = linkedMapOf(
        "Accounts" to p.accounts.size, "Categories" to p.categories.size, "Transactions" to p.transactions.size,
        "Scheduled payments" to p.schedules.size, "Lending & borrowing" to p.loans.size, "Repayments" to p.repayments.size,
        "Budgets" to p.budgets.size, "Savings goals" to p.goals.size,
    )

    private suspend fun replaceInTransaction(p: BackupPayload) {
        clearInTransaction()
        val d = db.backupDao()
        d.insertCategories(p.categories); d.insertAccounts(p.accounts); d.insertAdjustments(p.adjustments)
        d.insertPeople(p.people); d.insertSchedules(p.schedules); d.insertLoans(p.loans); d.insertRepayments(p.repayments)
        d.insertTransactions(p.transactions); d.insertOccurrences(p.occurrences); d.insertBudgets(p.budgets)
        d.insertGoals(p.goals); d.insertContributions(p.contributions); d.insertReminders(p.reminders); d.insertSettings(p.settings)
        p.notificationPreferences.firstOrNull()?.let { db.notificationDao().upsertPrefs(it.copy(id = 1)) }
        p.widgetPreferences.firstOrNull()?.let { db.widgetDao().upsertPrefs(it.copy(id = 1)) }
    }

    private fun verifyIntegrityInTransaction() {
        db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { c ->
            if (c.moveToFirst()) throw BackupException("The restored data failed an integrity check (table ${c.getString(0)}); nothing was changed")
        }
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
        safetyFile.delete()
    }

    // ---- Undo last restore ----

    private suspend fun writeSafetyCopy() = withContext(Dispatchers.IO) {
        val bytes = box.seal(encode(snapshot()))
        val tmp = File(context.filesDir, "restore_safety.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(safetyFile)) { safetyFile.delete(); tmp.renameTo(safetyFile) }
    }

    fun hasSafetyCopy(): Boolean = safetyFile.exists()
    fun safetyCopyTime(): Long? = safetyFile.takeIf { it.exists() }?.lastModified()

    /** Puts back the data that existed before the last restore. */
    suspend fun undoLastRestore(): RestoreReport {
        val bytes = withContext(Dispatchers.IO) { if (safetyFile.exists()) safetyFile.readBytes() else null }
            ?: throw BackupException("There's no earlier data to go back to")
        val p = try { decode(box.open(bytes)) } catch (e: BackupException) { throw e } catch (e: Exception) {
            throw BackupException("The safety copy can't be read on this device")
        }
        val report = restore(p, RestoreMode.REPLACE, keepSafetyCopy = false)
        safetyFile.delete()
        return report
    }

    // ---- Local file backups (Storage Access Framework) ----

    suspend fun exportTo(uri: Uri, password: CharArray): BackupStats = withContext(Dispatchers.IO) {
        val p = snapshot()
        val bytes = withContext(Dispatchers.Default) { BackupCrypto.encrypt(encode(p), password) }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw BackupException("Couldn't open the file")
        BackupStats(p.accounts.size, p.transactions.size)
    }

    suspend fun readFrom(uri: Uri, password: CharArray): BackupPayload = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Couldn't open the file")
        if (bytes.size > MAX_BACKUP_BYTES) throw BackupException("File is too large")
        decode(withContext(Dispatchers.Default) { BackupCrypto.decrypt(bytes, password) })
    }

    companion object { const val MAX_BACKUP_BYTES = 100L * 1024 * 1024 }
}
