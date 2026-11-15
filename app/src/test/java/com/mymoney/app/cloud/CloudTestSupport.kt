package com.mymoney.app.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mymoney.app.auth.GoogleAccountInfo
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.backup.BackupKeyStore
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.backup.KdfParams
import com.mymoney.app.data.Env
import com.mymoney.app.data.repo.ProfileRepository
import com.mymoney.app.security.FakeBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Instant

/** Fast Argon2 parameters for tests (the app uses the OWASP defaults). */
val TEST_KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
const val PASS = "orange tiger 42 river"

/** In-memory stand-in for the Drive appDataFolder. */
class FakeDrive : DriveRemote {
    val files = linkedMapOf<String, Pair<DriveFile, ByteArray>>()
    private var seq = 0
    var unauthorizedCalls = 0
    var corruptStoredMd5 = false
    var failUploadWith: Exception? = null
    val tokensSeen = mutableListOf<String>()

    private fun auth(token: String) {
        tokensSeen += token
        if (unauthorizedCalls > 0) { unauthorizedCalls--; throw DriveUnauthorizedException("expired") }
    }

    override suspend fun list(token: String): List<DriveFile> { auth(token); return files.values.map { it.first } }

    override suspend fun upload(token: String, name: String, bytes: ByteArray, appProperties: Map<String, String>): DriveFile {
        auth(token)
        failUploadWith?.let { throw it }
        val id = "f${++seq}"
        val md5 = if (corruptStoredMd5) "00000000000000000000000000000000" else BackupCrypto.md5Hex(bytes)
        val f = DriveFile(id, name, bytes.size.toString(), Instant.ofEpochSecond(1_700_000_000L + seq).toString(), md5, appProperties)
        files[id] = f to bytes.copyOf()
        return f
    }

    override suspend fun download(token: String, fileId: String, maxBytes: Long): ByteArray {
        auth(token); return files[fileId]?.second?.copyOf() ?: throw DriveApiException("missing", false)
    }

    override suspend fun delete(token: String, fileId: String) { auth(token); files.remove(fileId) }
}

class FakeAuthorizer : DriveAuthorizer {
    var needsUser = false
    var issued = 0
    val invalidated = mutableListOf<String>()
    val revoked = mutableListOf<String?>()
    override suspend fun authorize(email: String?): DriveAuthResult {
        if (needsUser) {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            return DriveAuthResult.NeedsUser(PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE))
        }
        return DriveAuthResult.Granted("token-${++issued}")
    }
    override fun tokenFromConsentResult(data: Intent?) = "consent-token"
    override suspend fun invalidate(token: String) { invalidated += token }
    override suspend fun revoke(email: String?) { revoked += email }
}

/** A "device": database + key store + profile + cloud service sharing one [drive]. */
class Device(val drive: FakeDrive, dir: File, name: String) {
    val ctx: Context = ApplicationProvider.getApplicationContext()
    val env = Env.inMemory()
    val auth = FakeAuthorizer()
    val keys = BackupKeyStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(dir, "$name.preferences_pb") }, FakeBox())
    val manager = BackupManager(ctx, env.db, FakeBox())
    val profiles = ProfileRepository(env.db)
    val service = CloudBackupService(env.db, manager, keys, drive, auth, profiles)

    suspend fun signInAndConnect(email: String = "user@example.com") {
        profiles.onSignedIn(GoogleAccountInfo("uid-$email", email, "Test User", null))
        profiles.setDriveConnected(true)
    }
}
