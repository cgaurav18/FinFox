package com.mymoney.app.security

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class SecurityState(
    val onboarded: Boolean = false,
    val pinSet: Boolean = false,
    val pinLength: Int = 4,
    val biometricEnabled: Boolean = false,
    /** Lock after the app has been in the background this long (0 = immediately). */
    val autoLockSeconds: Int = 30,
    val lockoutUntil: Long = 0,
)

sealed interface PinResult {
    data object Success : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    data class LockedOut(val untilMillis: Long) : PinResult
}

/**
 * Stores the PIN only as a salted PBKDF2-SHA256 hash, additionally sealed with an Android Keystore key.
 * Repeated failures trigger an escalating lockout. A one-time recovery code allows offline PIN reset.
 */
class PinManager(
    private val store: DataStore<Preferences>,
    private val box: SecretBox,
    private val iterations: Int = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private object K {
        val pinHash = stringPreferencesKey("pin_hash")
        val pinLength = intPreferencesKey("pin_length")
        val recoveryHash = stringPreferencesKey("recovery_hash")
        val biometric = booleanPreferencesKey("biometric")
        val autoLock = intPreferencesKey("auto_lock_seconds")
        val failed = intPreferencesKey("failed_attempts")
        val lockoutUntil = longPreferencesKey("lockout_until")
        val lockouts = intPreferencesKey("lockout_count")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_LOCKOUT_MS = 30_000L
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val AUTO_LOCK_OPTIONS = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes")

        fun isValidPin(pin: String) = (pin.length == 4 || pin.length == 6) && pin.all { it.isDigit() }
        fun isWeakPin(pin: String): Boolean {
            if (pin.toSet().size == 1) return true
            val asc = "0123456789012345"; val desc = "9876543210987654"
            return asc.contains(pin) || desc.contains(pin)
        }
    }

    val state: Flow<SecurityState> = store.data.map { p ->
        SecurityState(
            onboarded = p[K.onboarded] == true,
            pinSet = p[K.pinHash] != null,
            pinLength = p[K.pinLength] ?: 4,
            biometricEnabled = p[K.biometric] == true,
            autoLockSeconds = p[K.autoLock] ?: 30,
            lockoutUntil = p[K.lockoutUntil] ?: 0,
        )
    }

    suspend fun current(): SecurityState = state.first()

    /** Deliberately slow key derivation; always off the main thread. */
    private suspend fun hash(secret: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    private suspend fun seal(secret: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(box.seal(salt + hash(secret, salt)), Base64.NO_WRAP)
    }

    private suspend fun matches(secret: String, sealed: String?): Boolean {
        if (sealed == null) return false
        val raw = try { box.open(Base64.decode(sealed, Base64.NO_WRAP)) } catch (e: Exception) { return false }
        val salt = raw.copyOfRange(0, 16)
        return MessageDigest.isEqual(raw.copyOfRange(16, raw.size), hash(secret, salt))
    }

    suspend fun setPin(pin: String) {
        require(isValidPin(pin)) { "PIN must be 4 or 6 digits" }
        val sealed = seal(pin)
        store.edit {
            it[K.pinHash] = sealed; it[K.pinLength] = pin.length
            it[K.failed] = 0; it[K.lockoutUntil] = 0; it[K.lockouts] = 0
        }
    }

    suspend fun verify(pin: String): PinResult {
        val p = store.data.first()
        val now = clock()
        val until = p[K.lockoutUntil] ?: 0
        if (until > now) return PinResult.LockedOut(until)
        if (matches(pin, p[K.pinHash])) {
            store.edit { it[K.failed] = 0; it[K.lockouts] = 0; it[K.lockoutUntil] = 0 }
            return PinResult.Success
        }
        val failed = (p[K.failed] ?: 0) + 1
        return if (failed >= MAX_ATTEMPTS) {
            val n = (p[K.lockouts] ?: 0) + 1
            val lockUntil = now + BASE_LOCKOUT_MS * (1L shl (n - 1).coerceAtMost(6))
            store.edit { it[K.failed] = 0; it[K.lockouts] = n; it[K.lockoutUntil] = lockUntil }
            PinResult.LockedOut(lockUntil)
        } else {
            store.edit { it[K.failed] = failed }
            PinResult.Wrong(MAX_ATTEMPTS - failed)
        }
    }

    /** Generates a new recovery code, stores only its hash, and returns it once for the user to save. */
    suspend fun generateRecoveryCode(): String {
        val rnd = SecureRandom()
        val code = (1..4).joinToString("-") { (1..4).map { RECOVERY_ALPHABET[rnd.nextInt(RECOVERY_ALPHABET.length)] }.joinToString("") }
        val sealed = seal(normaliseCode(code))
        store.edit { it[K.recoveryHash] = sealed }
        return code
    }

    private fun normaliseCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    suspend fun hasRecoveryCode() = store.data.first()[K.recoveryHash] != null

    /** Resets the PIN using the recovery code. Uses the same attempt limits as the PIN. */
    suspend fun resetWithRecoveryCode(code: String, newPin: String): PinResult {
        val p = store.data.first()
        val now = clock()
        val until = p[K.lockoutUntil] ?: 0
        if (until > now) return PinResult.LockedOut(until)
        if (!matches(normaliseCode(code), p[K.recoveryHash])) {
            val failed = (p[K.failed] ?: 0) + 1
            return if (failed >= MAX_ATTEMPTS) {
                val n = (p[K.lockouts] ?: 0) + 1
                val lockUntil = now + BASE_LOCKOUT_MS * (1L shl (n - 1).coerceAtMost(6))
                store.edit { it[K.failed] = 0; it[K.lockouts] = n; it[K.lockoutUntil] = lockUntil }
                PinResult.LockedOut(lockUntil)
            } else { store.edit { it[K.failed] = failed }; PinResult.Wrong(MAX_ATTEMPTS - failed) }
        }
        setPin(newPin)
        return PinResult.Success
    }

    /** Successful biometric authentication also clears failed PIN attempts. */
    suspend fun onBiometricSuccess() = store.edit { it[K.failed] = 0 }

    suspend fun setBiometric(enabled: Boolean) = store.edit { it[K.biometric] = enabled }
    suspend fun setAutoLock(seconds: Int) = store.edit { it[K.autoLock] = seconds }
    suspend fun completeOnboarding() = store.edit { it[K.onboarded] = true }
    suspend fun clearAll() = store.edit { it.clear() }
}
