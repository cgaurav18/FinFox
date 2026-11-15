package com.mymoney.app.cloud

import android.os.Build
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.backup.BackupException
import com.mymoney.app.backup.BackupKey
import com.mymoney.app.backup.BackupKeyStore
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.backup.BackupPayload
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BackupMetadataEntity
import com.mymoney.app.data.db.BackupPreferencesEntity
import com.mymoney.app.data.db.BackupStatus
import com.mymoney.app.data.db.BackupTrigger
import com.mymoney.app.data.repo.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Why a cloud backup operation couldn't run. [message] is user facing. */
sealed class CloudBackupException(message: String, val retryable: Boolean = false) : Exception(message) {
    class DriveNotConnected : CloudBackupException("Connect Google Drive first")
    class NoPassphrase : CloudBackupException("Set a recovery passphrase before backing up")
    class NeedsReauth : CloudBackupException("Google Drive needs your permission again. Open Backup & Restore to reconnect.")
    class Failed(message: String, retryable: Boolean) : CloudBackupException(message, retryable)
}

data class RemoteBackup(
    val fileId: String,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val md5: String?,
    val device: String?,
    val transactions: Int?,
    val accounts: Int?,
    val formatVersion: Int?,
)

data class BackupOutcome(val metadata: BackupMetadataEntity)

@Singleton
class CloudBackupService @Inject constructor(
    private val db: AppDatabase,
    private val backup: BackupManager,
    private val keys: BackupKeyStore,
    private val drive: DriveRemote,
    private val authorizer: DriveAuthorizer,
    private val profiles: ProfileRepository,
) {
    private val dao = db.cloudBackupDao()

    val prefs: Flow<BackupPreferencesEntity> = dao.observePrefs().map { it ?: BackupPreferencesEntity() }
    suspend fun getPrefs() = dao.getPrefs() ?: BackupPreferencesEntity()
    suspend fun updatePrefs(t: (BackupPreferencesEntity) -> BackupPreferencesEntity) = dao.upsertPrefs(t(getPrefs()))
    fun history() = dao.observeHistory()

    private suspend fun requireReady(): String? {
        val p = profiles.get()
        if (!p.driveConnected) throw CloudBackupException.DriveNotConnected()
        return p.email
    }

    /** Runs [block] with a fresh access token; on 401 clears the cached token and retries once. */
    private suspend fun <T> withToken(email: String?, block: suspend (String) -> T): T {
        suspend fun token(): String = when (val r = try { authorizer.authorize(email) } catch (e: DriveAuthException) {
            throw CloudBackupException.Failed(e.message ?: "Authorization failed", retryable = true)
        }) {
            is DriveAuthResult.Granted -> r.accessToken
            is DriveAuthResult.NeedsUser -> { profiles.setDriveNeedsReauth(true); throw CloudBackupException.NeedsReauth() }
        }
        val first = token()
        val result = try { block(first) } catch (e: DriveUnauthorizedException) {
            authorizer.invalidate(first)
            val second = token()
            try { block(second) } catch (e2: DriveUnauthorizedException) {
                authorizer.invalidate(second)
                profiles.setDriveNeedsReauth(true)
                throw CloudBackupException.NeedsReauth()
            }
        }
        if (profiles.get().driveNeedsReauth) profiles.setDriveNeedsReauth(false)
        return result
    }

    private fun mapDrive(e: DriveApiException) = CloudBackupException.Failed(e.message ?: "Google Drive error", e.retryable)

    /**
     * Snapshot → serialize → encrypt locally (AES-256-GCM, key derived from the recovery passphrase) →
     * upload to appDataFolder → verify size and MD5 → record metadata → prune old backups.
     */
    suspend fun backupNow(trigger: BackupTrigger): BackupOutcome {
        val now = Dates.nowMillis()
        val email = try { requireReady() } catch (e: CloudBackupException) { recordFailure(trigger, null, e.message); throw e }
        val key: BackupKey = keys.key() ?: run {
            val e = CloudBackupException.NoPassphrase(); recordFailure(trigger, email, e.message); throw e
        }
        try {
            val payload = backup.snapshot()
            val encrypted = withContext(Dispatchers.Default) { BackupCrypto.encryptWithKey(backup.encode(payload), key) }
            val sha = BackupCrypto.sha256Hex(encrypted)
            val name = "finfox-backup-" + NAME_FMT.format(Instant.ofEpochMilli(now)) + ".mmbk"
            val props = mapOf(
                "app" to "mymoney", "format" to BackupPayload.CURRENT_FORMAT.toString(), "schema" to AppDatabase.VERSION.toString(),
                "device" to (Build.MODEL ?: "Android").take(60), "transactions" to payload.transactions.size.toString(),
                "accounts" to payload.accounts.size.toString(), "sha256" to sha,
            )
            val remote = withToken(email) { t -> drive.upload(t, name, encrypted, props) }
            // Verify what Drive stored is byte-identical to what we encrypted.
            val sizeOk = remote.size?.toLongOrNull() == encrypted.size.toLong()
            val md5Ok = remote.md5Checksum == null || remote.md5Checksum.equals(BackupCrypto.md5Hex(encrypted), ignoreCase = true)
            if (!sizeOk || !md5Ok) {
                try { withToken(email) { t -> drive.delete(t, remote.id) } } catch (_: Exception) { }
                throw CloudBackupException.Failed("Upload verification failed — the backup was discarded. Try again.", retryable = true)
            }
            val meta = BackupMetadataEntity(
                driveFileId = remote.id, fileName = name, accountEmail = email, createdAt = now, sizeBytes = encrypted.size.toLong(),
                sha256 = sha, trigger = trigger, status = BackupStatus.SUCCESS, accountCount = payload.accounts.size, transactionCount = payload.transactions.size,
            )
            val id = dao.insertMetadata(meta)
            updatePrefs { it.copy(lastSuccessAt = now, lastSizeBytes = meta.sizeBytes, lastAttemptAt = now, lastError = null) }
            dao.trimHistory()
            try { prune(email) } catch (_: Exception) { /* pruning is best effort */ }
            return BackupOutcome(meta.copy(id = id))
        } catch (e: CloudBackupException) {
            recordFailure(trigger, email, e.message); throw e
        } catch (e: DriveApiException) {
            val m = mapDrive(e); recordFailure(trigger, email, m.message); throw m
        } catch (e: BackupException) {
            recordFailure(trigger, email, e.message); throw CloudBackupException.Failed(e.message ?: "Backup failed", false)
        }
    }

    private suspend fun recordFailure(trigger: BackupTrigger, email: String?, message: String?) {
        val now = Dates.nowMillis()
        dao.insertMetadata(BackupMetadataEntity(fileName = "-", accountEmail = email, createdAt = now, sizeBytes = 0, sha256 = null, trigger = trigger, status = BackupStatus.FAILED, error = message))
        updatePrefs { it.copy(lastAttemptAt = now, lastError = message) }
        dao.trimHistory()
    }

    /** Deletes the oldest backups beyond the configured keep count. */
    private suspend fun prune(email: String?) {
        val keep = getPrefs().keepCount.coerceAtLeast(1)
        val files = withToken(email) { t -> drive.list(t) }.filter { it.isOurs() }.sortedByDescending { it.createdMillis() }
        files.drop(keep).forEach { f ->
            withToken(email) { t -> drive.delete(t, f.id) }
            dao.deleteForFile(f.id)
        }
    }

    suspend fun listBackups(): List<RemoteBackup> {
        val email = requireReady()
        val files = try { withToken(email) { t -> drive.list(t) } } catch (e: DriveApiException) { throw mapDrive(e) }
        return files.filter { it.isOurs() }.map {
            RemoteBackup(
                it.id, it.name ?: it.id, it.createdMillis(), it.size?.toLongOrNull() ?: 0, it.md5Checksum,
                it.appProperties?.get("device"), it.appProperties?.get("transactions")?.toIntOrNull(),
                it.appProperties?.get("accounts")?.toIntOrNull(), it.appProperties?.get("format")?.toIntOrNull(),
            )
        }.sortedByDescending { it.createdAt }
    }

    /** Downloads an encrypted backup and checks it against Drive's checksum. */
    suspend fun download(b: RemoteBackup): ByteArray {
        val email = requireReady()
        val bytes = try { withToken(email) { t -> drive.download(t, b.fileId, BackupManager.MAX_BACKUP_BYTES) } } catch (e: DriveApiException) { throw mapDrive(e) }
        if (b.md5 != null && !b.md5.equals(BackupCrypto.md5Hex(bytes), ignoreCase = true)) {
            throw CloudBackupException.Failed("The downloaded backup is corrupted (checksum mismatch)", retryable = true)
        }
        return bytes
    }

    /**
     * Verifies authenticity (GCM tag) and decrypts with the recovery passphrase, then validates the schema.
     * When [rememberKey] the derived key is kept so this device continues backing up under that passphrase.
     */
    suspend fun decrypt(bytes: ByteArray, passphrase: CharArray, rememberKey: Boolean): BackupPayload = withContext(Dispatchers.Default) {
        val h = BackupCrypto.readHeader(bytes)
        val payload: BackupPayload
        if (h.version == 2) {
            val key = BackupCrypto.argon2id(passphrase, h.salt, h.params!!)
            payload = backup.decode(BackupCrypto.decryptWithKey(bytes, key))
            if (rememberKey && keys.key() == null) keys.save(BackupKey(key, h.salt, h.params))
        } else payload = backup.decode(BackupCrypto.decrypt(bytes, passphrase))
        payload
    }

    suspend fun deleteBackup(b: RemoteBackup) {
        val email = requireReady()
        try { withToken(email) { t -> drive.delete(t, b.fileId) } } catch (e: DriveApiException) { throw mapDrive(e) }
        dao.deleteForFile(b.fileId)
    }

    private fun DriveFile.isOurs() = appProperties?.get("app") == "mymoney" || (name?.startsWith("finfox-backup-") == true || name?.startsWith("mymoney-backup-") == true)
    private fun DriveFile.createdMillis() = try { Instant.parse(createdTime).toEpochMilli() } catch (e: Exception) { 0L }

    companion object {
        private val NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
