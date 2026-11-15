package com.mymoney.app.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.backup.BackupException
import com.mymoney.app.backup.RestoreMode
import com.mymoney.app.core.Dates
import com.mymoney.app.data.FakeNotifier
import com.mymoney.app.data.R
import com.mymoney.app.data.db.BackupStatus
import com.mymoney.app.data.db.BackupTrigger
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.TxnInput
import com.mymoney.app.notifications.BackupEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudBackupServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val drive = FakeDrive()
    private lateinit var a: Device

    @Before fun setUp() = runTest {
        a = Device(drive, tmp.root, "a")
        a.env.seed()
        a.signInAndConnect()
        a.keys.setPassphrase(PASS.toCharArray(), TEST_KDF)
    }
    @After fun tearDown() { a.env.db.close() }

    private suspend fun populate(d: Device): Long {
        val bank = d.env.bank(opening = 10_000 * R)
        d.env.txns.save(TxnInput(type = TxnType.INCOME, amountMinor = 2_500 * R, accountId = bank, categoryId = d.env.cat("Salary", CategoryType.INCOME), dateMillis = Dates.nowMillis()))
        d.env.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 400 * R, accountId = bank, categoryId = d.env.cat("Food"), dateMillis = Dates.nowMillis()))
        return bank
    }

    @Test fun backupUploadsEncryptedVerifiedFileAndRecordsMetadata() = runTest {
        populate(a)
        val out = a.service.backupNow(BackupTrigger.MANUAL).metadata
        assertThat(drive.files).hasSize(1)
        val (file, bytes) = drive.files.values.single()
        assertThat(file.appProperties!!["app"]).isEqualTo("mymoney")
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("Salary") // encrypted at rest in Drive
        assertThat(out.status).isEqualTo(BackupStatus.SUCCESS)
        assertThat(out.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(out.transactionCount).isEqualTo(2)
        assertThat(a.service.getPrefs().lastSuccessAt).isNotNull()
        // The access token is used in memory only.
        assertThat(drive.tokensSeen).isNotEmpty()
    }

    @Test fun restoreOnAnotherDeviceWithPassphrase() = runTest {
        val bank = populate(a)
        a.service.backupNow(BackupTrigger.MANUAL)

        val b = Device(drive, tmp.root, "b")
        b.env.seed(); b.signInAndConnect()
        assertThat(b.keys.key()).isNull()
        val remote = b.service.listBackups().single()
        val bytes = b.service.download(remote)
        assertThrows(BackupException::class.java) { runBlocking { b.service.decrypt(bytes, "wrong passphrase!!".toCharArray(), rememberKey = true) } }
        val payload = b.service.decrypt(bytes, PASS.toCharArray(), rememberKey = true)
        val report = b.manager.restore(payload, RestoreMode.REPLACE)
        assertThat(b.env.balance(bank)).isEqualTo(a.env.balance(bank))
        assertThat(report.availableBalanceMinor).isEqualTo((10_000 + 2_500 - 400) * R)
        // Device B adopted the passphrase key, so its own backups can be restored on device A.
        assertThat(b.keys.verify(PASS.toCharArray())).isTrue()
        b.env.db.close()
    }

    @Test fun expiredTokenIsClearedAndRetriedOnce() = runTest {
        populate(a)
        drive.unauthorizedCalls = 1
        a.service.backupNow(BackupTrigger.AUTO)
        assertThat(a.auth.invalidated).containsExactly("token-1")
        assertThat(drive.files).hasSize(1)
        assertThat(a.profiles.get().driveNeedsReauth).isFalse()
    }

    @Test fun revokedGrantNeedsReauthorizationAndIsRecorded() = runTest {
        a.auth.needsUser = true
        assertThrows(CloudBackupException.NeedsReauth::class.java) { runBlocking { a.service.backupNow(BackupTrigger.AUTO) } }
        assertThat(a.profiles.get().driveNeedsReauth).isTrue()
        assertThat(a.env.db.cloudBackupDao().observeHistory().first().first().status).isEqualTo(BackupStatus.FAILED)
        assertThat(drive.files).isEmpty()
    }

    @Test fun repeatedUnauthorizedNeedsReauthorization() = runTest {
        drive.unauthorizedCalls = 5
        assertThrows(CloudBackupException.NeedsReauth::class.java) { runBlocking { a.service.backupNow(BackupTrigger.AUTO) } }
        assertThat(a.auth.invalidated).hasSize(2)
    }

    @Test fun failedVerificationDiscardsTheUpload() = runTest {
        drive.corruptStoredMd5 = true
        val e = assertThrows(CloudBackupException.Failed::class.java) { runBlocking { a.service.backupNow(BackupTrigger.MANUAL) } }
        assertThat(e.message).contains("verification")
        assertThat(drive.files).isEmpty()
        assertThat(a.service.getPrefs().lastError).contains("verification")
    }

    @Test fun preconditionsAreEnforced() = runTest {
        a.keys.clear()
        assertThrows(CloudBackupException.NoPassphrase::class.java) { runBlocking { a.service.backupNow(BackupTrigger.MANUAL) } }
        a.profiles.setDriveConnected(false)
        assertThrows(CloudBackupException.DriveNotConnected::class.java) { runBlocking { a.service.backupNow(BackupTrigger.MANUAL) } }
        a.profiles.onDriveDisconnected()
        assertThrows(CloudBackupException.DriveNotConnected::class.java) { runBlocking { a.service.backupNow(BackupTrigger.MANUAL) } }
        assertThat(drive.files).isEmpty()
    }

    @Test fun oldBackupsArePrunedToKeepCount() = runTest {
        a.service.updatePrefs { it.copy(keepCount = 2) }
        repeat(4) { a.service.backupNow(BackupTrigger.AUTO) }
        assertThat(drive.files.keys).containsExactly("f3", "f4")
        assertThat(a.service.listBackups().map { it.fileId }).containsExactly("f4", "f3").inOrder()
    }

    @Test fun corruptedDownloadIsDetected() = runTest {
        a.service.backupNow(BackupTrigger.MANUAL)
        val remote = a.service.listBackups().single()
        val id = remote.fileId
        drive.files[id] = drive.files[id]!!.first to drive.files[id]!!.second.also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val e = assertThrows(CloudBackupException.Failed::class.java) { runBlocking { a.service.download(remote) } }
        assertThat(e.message).contains("corrupted")
    }

    // ---- WorkManager worker ----

    private fun worker(attempt: Int, trigger: BackupTrigger, notifier: FakeNotifier): BackupWorker {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<BackupWorker>(ctx)
            .setRunAttemptCount(attempt)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to trigger.name))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    BackupWorker(appContext, workerParameters, a.service, notifier)
            }).build()
    }

    @Test fun workerRetriesTransientFailuresThenReportsFailure() = runTest {
        val n = FakeNotifier()
        drive.failUploadWith = DriveApiException("Google Drive is busy", retryable = true)
        assertThat(worker(0, BackupTrigger.MANUAL, n).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(1, BackupTrigger.MANUAL, n).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(BackupWorker.MAX_ATTEMPTS - 1, BackupTrigger.MANUAL, n).doWork()).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(n.backups).containsExactly(BackupEvent.FAILED)
        drive.failUploadWith = null
        assertThat(worker(0, BackupTrigger.MANUAL, n).doWork()).isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    @Test fun workerStopsAndNotifiesWhenAuthorizationExpired() = runTest {
        val n = FakeNotifier()
        a.service.updatePrefs { it.copy(autoBackupEnabled = true) }
        a.auth.needsUser = true
        assertThat(worker(0, BackupTrigger.AUTO, n).doWork()).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(n.backups).containsExactly(BackupEvent.AUTH_EXPIRED)
    }

    @Test fun autoWorkerDoesNothingWhenAutoBackupIsOff() = runTest {
        val n = FakeNotifier()
        assertThat(worker(0, BackupTrigger.AUTO, n).doWork()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(drive.files).isEmpty()
        assertThat(n.backups).isEmpty()
    }

    @Test fun autoWorkerNotifiesSuccess() = runTest {
        val n = FakeNotifier()
        a.service.updatePrefs { it.copy(autoBackupEnabled = true) }
        assertThat(worker(0, BackupTrigger.AUTO, n).doWork()).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(n.backups).containsExactly(BackupEvent.SUCCESS)
    }
}
