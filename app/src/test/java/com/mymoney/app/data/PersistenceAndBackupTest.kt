package com.mymoney.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.backup.BackupException
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.core.Dates
import com.mymoney.app.security.FakeBox
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.Migrations
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.GoalInput
import com.mymoney.app.data.repo.LoanInput
import com.mymoney.app.data.repo.ScheduleInput
import com.mymoney.app.data.repo.TxnInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceAndBackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun fileDb(name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
        .addMigrations(*Migrations.ALL)
        .setQueryExecutor(Executors.newSingleThreadExecutor())
        .setTransactionExecutor(Executors.newSingleThreadExecutor())
        .build()

    private suspend fun populate(e: Env): Long {
        e.seed()
        val bank = e.bank(opening = 10_000 * R)
        val card = e.card(outstanding = 1_000 * R)
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 500 * R, accountId = card, categoryId = e.cat("Food"), dateMillis = Dates.nowMillis(), tags = "x"))
        e.txns.save(TxnInput(type = TxnType.INCOME, amountMinor = 2_000 * R, accountId = bank, categoryId = e.cat("Salary", CategoryType.INCOME), dateMillis = Dates.nowMillis()))
        val loan = e.loans.saveLoan(LoanInput(personName = "Amit", direction = LoanDirection.GAVE, amountMinor = 3_000 * R, dateMillis = Dates.nowMillis(), accountId = bank))
        e.loans.addRepayment(loan, 1_000 * R, Dates.nowMillis(), bank, null)
        val s = e.schedules.save(ScheduleInput(title = "SIP", category = ScheduleCategory.SIP, amountMinor = 1_000 * R, accountId = bank, start = Dates.today(), frequency = Frequency.MONTHLY, nextDue = Dates.today()))
        e.schedules.markPaid(s)
        e.budgets.save(null, e.cat("Food"), java.time.YearMonth.now(), 5_000 * R)
        val g = e.goals.save(GoalInput(name = "Trip", targetMinor = 5_000 * R, initialSavedMinor = 1_000 * R, colorArgb = 0))
        e.goals.contribute(g, 500 * R, Dates.nowMillis(), null)
        return bank
    }

    @Test fun dataPersistsAcrossRestart() = runTest {
        val name = "persist_test.db"
        ctx.deleteDatabase(name)
        val first = Env(fileDb(name))
        val bank = populate(first)
        val before = first.balance(bank)
        first.db.close()

        val second = Env(fileDb(name))
        assertThat(second.balance(bank)).isEqualTo(before)
        assertThat(second.balance(bank)).isEqualTo((10_000 + 2_000 - 3_000 + 1_000 - 1_000) * R)
        assertThat(second.db.transactionDao().count()).isEqualTo(5)
        second.db.close()
        ctx.deleteDatabase(name)
    }

    @Test fun encryptedBackupRestoresEverythingExactly() = runTest {
        val src = Env.inMemory()
        val bank = populate(src)
        val mgr = BackupManager(ctx, src.db, FakeBox())
        val snapshot = mgr.snapshot()
        val file = BackupCrypto.encrypt(mgr.encode(snapshot), "my backup pw".toCharArray())

        val dst = Env.inMemory()
        dst.seed()
        dst.bank("Other", 99 * R) // existing data is replaced
        val dstMgr = BackupManager(ctx, dst.db, FakeBox())
        dstMgr.replaceAll(dstMgr.decode(BackupCrypto.decrypt(file, "my backup pw".toCharArray())))

        assertThat(dstMgr.snapshot().copy(exportedAt = 0)).isEqualTo(snapshot.copy(exportedAt = 0))
        assertThat(dst.balance(bank)).isEqualTo(src.balance(bank))
        assertThat(dst.loans.getLoan(1)!!.outstandingMinor).isEqualTo(2_000 * R)
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file, "wrong password".toCharArray()) }
        src.db.close(); dst.db.close()
    }

    @Test fun failedRestoreLeavesDataUntouched() = runTest {
        val e = Env.inMemory()
        val bank = populate(e)
        val mgr = BackupManager(ctx, e.db, FakeBox())
        val good = mgr.snapshot()
        // A corrupt payload: transaction pointing at a missing account violates a foreign key mid-restore.
        val bad = good.copy(transactions = good.transactions + good.transactions.first().copy(id = 999, accountId = 4242))
        assertThrows(Exception::class.java) { runBlocking { mgr.replaceAll(bad) } }
        assertThat(e.balance(bank)).isEqualTo((10_000 + 2_000 - 3_000 + 1_000 - 1_000) * R)
        assertThat(e.db.transactionDao().count()).isEqualTo(5)
        e.db.close()
    }

    @Test fun resetWipesAndReseeds() = runTest {
        val e = Env.inMemory()
        populate(e)
        BackupManager(ctx, e.db, FakeBox()).resetAll()
        assertThat(e.db.transactionDao().count()).isEqualTo(0)
        assertThat(e.db.backupDao().accounts()).isEmpty()
        assertThat(e.db.categoryDao().count()).isGreaterThan(0)
        e.db.close()
    }
}
