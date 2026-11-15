package com.mymoney.app.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.Frequency
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.NotificationPreferencesEntity
import com.mymoney.app.data.db.ScheduleCategory
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.db.UserProfileEntity
import com.mymoney.app.data.db.WidgetPreferencesEntity
import com.mymoney.app.data.repo.GoalInput
import com.mymoney.app.data.repo.LoanInput
import com.mymoney.app.data.repo.ScheduleInput
import com.mymoney.app.data.repo.TxnInput
import com.mymoney.app.security.FakeBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreAndKeysTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun populate(e: Env): Long {
        e.seed()
        val bank = e.bank(opening = 10_000 * R)
        val card = e.card(outstanding = 1_000 * R)
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 500 * R, accountId = card, categoryId = e.cat("Food"), dateMillis = 1_700_000_000_000))
        e.txns.save(TxnInput(type = TxnType.INCOME, amountMinor = 2_000 * R, accountId = bank, categoryId = e.cat("Salary", CategoryType.INCOME), dateMillis = 1_700_000_100_000))
        val loan = e.loans.saveLoan(LoanInput(personName = "Amit", direction = LoanDirection.GAVE, amountMinor = 3_000 * R, dateMillis = 1_700_000_000_000, accountId = bank))
        e.loans.addRepayment(loan, 1_000 * R, 1_700_000_200_000, bank, null)
        val s = e.schedules.save(ScheduleInput(title = "SIP", category = ScheduleCategory.SIP, amountMinor = 1_000 * R, accountId = bank, start = Dates.today(), frequency = Frequency.MONTHLY, nextDue = Dates.today()))
        e.schedules.markPaid(s)
        e.budgets.save(null, e.cat("Food"), YearMonth.now(), 5_000 * R)
        val g = e.goals.save(GoalInput(name = "Trip", targetMinor = 5_000 * R, initialSavedMinor = 1_000 * R, colorArgb = 0))
        e.goals.contribute(g, 500 * R, 1_700_000_000_000, null)
        return bank
    }

    private fun mgr(e: Env) = BackupManager(ctx, e.db, FakeBox())

    @Test fun mergingTheSameBackupTwiceAddsNothing() = runTest {
        val e = Env.inMemory()
        val bank = populate(e)
        val before = e.balance(bank)
        val m = mgr(e)
        val p = m.snapshot()
        val r1 = m.restore(p, RestoreMode.MERGE)
        assertThat(r1.totalInserted).isEqualTo(0)
        assertThat(r1.totalSkipped).isGreaterThan(0)
        val r2 = m.restore(m.snapshot(), RestoreMode.MERGE)
        assertThat(r2.totalInserted).isEqualTo(0)
        assertThat(e.balance(bank)).isEqualTo(before)
        assertThat(e.db.transactionDao().count()).isEqualTo(p.transactions.size)
        e.db.close()
    }

    @Test fun mergeIntoAnotherDeviceRemapsIdsAndKeepsExistingRecords() = runTest {
        val src = Env.inMemory(); populate(src)
        val payload = mgr(src).snapshot()

        val dst = Env.inMemory(); dst.seed()
        val cash = dst.cash(opening = 777 * R) // id 1 here — collides with the backup's bank id
        dst.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 50 * R, accountId = cash, categoryId = dst.cat("Food"), dateMillis = Dates.nowMillis()))
        val report = mgr(dst).restore(payload, RestoreMode.MERGE)

        assertThat(report.inserted["Accounts"]).isEqualTo(2)
        assertThat(report.inserted["Transactions"]).isEqualTo(payload.transactions.size)
        assertThat(dst.balance(cash)).isEqualTo((777 - 50) * R) // untouched
        val hdfc = dst.db.backupDao().accounts().single { it.name == "HDFC" }
        assertThat(dst.balance(hdfc.id)).isEqualTo((10_000 + 2_000 - 3_000 + 1_000 - 1_000) * R)
        assertThat(dst.loans.getLoan(dst.db.backupDao().loans().single().id)!!.outstandingMinor).isEqualTo(2_000 * R)
        // Re-merging is idempotent.
        assertThat(mgr(dst).restore(payload, RestoreMode.MERGE).totalInserted).isEqualTo(0)
        src.db.close(); dst.db.close()
    }

    @Test fun replaceKeepsSafetyCopyAndCanBeUndone() = runTest {
        val src = Env.inMemory(); val srcBank = populate(src)
        val payload = mgr(src).snapshot()
        val dst = Env.inMemory(); dst.seed()
        val mine = dst.cash(opening = 123 * R)
        val m = mgr(dst)
        m.restore(payload, RestoreMode.REPLACE)
        assertThat(dst.balance(srcBank)).isEqualTo(src.balance(srcBank))
        assertThat(m.hasSafetyCopy()).isTrue()
        m.undoLastRestore()
        assertThat(dst.db.backupDao().accounts().map { it.id }).containsExactly(mine)
        assertThat(dst.balance(mine)).isEqualTo(123 * R)
        assertThat(m.hasSafetyCopy()).isFalse()
        src.db.close(); dst.db.close()
    }

    @Test fun corruptedOrNewerBackupsAreRejectedWithoutTouchingData() = runTest {
        val e = Env.inMemory(); val bank = populate(e)
        val m = mgr(e)
        val good = m.snapshot()
        val bad = good.copy(transactions = good.transactions + good.transactions.first().copy(id = 999, accountId = 4242))
        val err = assertThrows(BackupException::class.java) { runBlocking { m.restore(bad, RestoreMode.REPLACE) } }
        assertThat(err.message).contains("corrupted")
        assertThrows(BackupException::class.java) { runBlocking { m.restore(good.copy(schemaVersion = 99), RestoreMode.MERGE) } }
        assertThrows(BackupException::class.java) { m.decode("{not json".toByteArray()) }
        assertThat(e.balance(bank)).isEqualTo((10_000 + 2_000 - 3_000 + 1_000 - 1_000) * R)
        e.db.close()
    }

    @Test fun preferencesAreBackedUpButNeverIdentityOrSecrets() = runTest {
        val src = Env.inMemory(); populate(src)
        src.db.notificationDao().upsertPrefs(NotificationPreferencesEntity(quietHoursEnabled = true, snoozeHours = 6))
        src.db.widgetDao().upsertPrefs(WidgetPreferencesEntity(privacyMode = true))
        src.db.profileDao().upsert(UserProfileEntity(googleUid = "uid", email = "secret@example.com", signedIn = true, driveConnected = true))
        val m = mgr(src)
        val json = m.encode(m.snapshot()).decodeToString()
        assertThat(json).doesNotContain("secret@example.com")
        assertThat(json).doesNotContain("googleUid")
        assertThat(json).doesNotContain("\"pin")
        assertThat(json).doesNotContain("passphrase")
        assertThat(json).doesNotContain("driveConnected")

        val dst = Env.inMemory(); dst.seed()
        mgr(dst).restore(m.decode(json.toByteArray()), RestoreMode.REPLACE)
        assertThat(dst.db.notificationDao().getPrefs()!!.snoozeHours).isEqualTo(6)
        assertThat(dst.db.widgetDao().getPrefs()!!.privacyMode).isTrue()
        assertThat(dst.db.profileDao().get()).isNull()
        src.db.close(); dst.db.close()
    }

    // ---- Recovery key store ----

    private fun keyStore(name: String) = BackupKeyStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(tmp.root, "$name.preferences_pb") }, FakeBox(),
    )

    @Test fun passphraseKeyIsSealedVerifiableAndClearable() = runTest {
        val ks = keyStore("k1")
        val fast = KdfParams(8 * 1024, 1, 1)
        assertThrows(BackupException::class.java) { runBlocking { ks.setPassphrase("short".toCharArray(), fast) } }
        ks.setPassphrase("orange tiger 42 river".toCharArray(), fast)
        val k = ks.key()!!
        assertThat(k.params).isEqualTo(fast)
        assertThat(ks.verify("orange tiger 42 river".toCharArray())).isTrue()
        assertThat(ks.verify("orange tiger 42 rivers".toCharArray())).isFalse()
        // Neither the passphrase nor the raw key is written to disk.
        val onDisk = File(tmp.root, "k1.preferences_pb").readBytes()
        assertThat(String(onDisk, Charsets.ISO_8859_1)).doesNotContain("orange tiger")
        assertThat(String(onDisk, Charsets.ISO_8859_1)).doesNotContain(android.util.Base64.encodeToString(k.key, android.util.Base64.NO_WRAP))
        // A file encrypted with the stored key opens with just the passphrase.
        val file = BackupCrypto.encryptWithKey("data".toByteArray(), k)
        assertThat(BackupCrypto.decrypt(file, "orange tiger 42 river".toCharArray())).isEqualTo("data".toByteArray())
        ks.clear()
        assertThat(ks.key()).isNull()
    }
}
