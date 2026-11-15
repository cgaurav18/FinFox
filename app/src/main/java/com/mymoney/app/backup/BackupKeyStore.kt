package com.mymoney.app.backup

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymoney.app.security.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Holds the key derived from the user's cloud recovery passphrase so scheduled backups can run without
 * asking for it. The passphrase itself is never stored; the 32-byte Argon2id output is sealed with a
 * non-exportable Android Keystore key, so a copied app-data directory can't reveal it. The key never
 * leaves the device and is never uploaded with a backup — another device re-derives it from the
 * passphrase plus the salt/params stored in the backup header.
 */
class BackupKeyStore(private val store: DataStore<Preferences>, private val box: SecretBox) {
    private object K {
        val sealedKey = stringPreferencesKey("backup_key_sealed")
        val salt = stringPreferencesKey("backup_key_salt")
        val mem = intPreferencesKey("backup_kdf_mem")
        val iter = intPreferencesKey("backup_kdf_iter")
        val par = intPreferencesKey("backup_kdf_par")
        val setAt = longPreferencesKey("backup_key_set_at")
    }

    val hasPassphrase: Flow<Boolean> = store.data.map { it[K.sealedKey] != null }
    val passphraseSetAt: Flow<Long?> = store.data.map { it[K.setAt] }

    suspend fun setPassphrase(passphrase: CharArray, params: KdfParams = KdfParams()) {
        if (passphrase.size < BackupCrypto.MIN_PASSPHRASE) throw BackupException("Use at least ${BackupCrypto.MIN_PASSPHRASE} characters")
        val k = withContext(Dispatchers.Default) { BackupCrypto.newKey(passphrase, params) }
        save(k)
    }

    /** Stores a key re-derived during restore so this device keeps backing up under the same passphrase. */
    suspend fun save(k: BackupKey) {
        val sealed = Base64.encodeToString(box.seal(k.key), Base64.NO_WRAP)
        store.edit {
            it[K.sealedKey] = sealed
            it[K.salt] = Base64.encodeToString(k.salt, Base64.NO_WRAP)
            it[K.mem] = k.params.memoryKiB; it[K.iter] = k.params.iterations; it[K.par] = k.params.parallelism
            it[K.setAt] = System.currentTimeMillis()
        }
    }

    suspend fun key(): BackupKey? {
        val p = store.data.first()
        val sealed = p[K.sealedKey] ?: return null
        val raw = try { box.open(Base64.decode(sealed, Base64.NO_WRAP)) } catch (e: Exception) {
            // Keystore key lost (e.g. device restore): the user must enter the passphrase again.
            return null
        }
        return BackupKey(raw, Base64.decode(p[K.salt], Base64.NO_WRAP), KdfParams(p[K.mem] ?: 0, p[K.iter] ?: 0, p[K.par] ?: 0))
    }

    /** True when [passphrase] re-derives the stored key. */
    suspend fun verify(passphrase: CharArray): Boolean {
        val k = key() ?: return false
        val d = withContext(Dispatchers.Default) { BackupCrypto.argon2id(passphrase, k.salt, k.params) }
        return MessageDigest.isEqual(d, k.key)
    }

    suspend fun clear() = store.edit {
        it.remove(K.sealedKey); it.remove(K.salt); it.remove(K.mem); it.remove(K.iter); it.remove(K.par); it.remove(K.setAt)
    }
}
