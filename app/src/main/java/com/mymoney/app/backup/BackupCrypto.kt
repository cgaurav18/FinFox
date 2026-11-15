package com.mymoney.app.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/** Argon2id cost parameters (defaults follow the OWASP minimum: 19 MiB, 2 passes, 1 lane). */
data class KdfParams(val memoryKiB: Int = 19 * 1024, val iterations: Int = 2, val parallelism: Int = 1) {
    fun isSane() = memoryKiB in 8 * 1024..128 * 1024 && iterations in 1..10 && parallelism in 1..4
}

/** A derived backup key together with the public inputs needed to re-derive it from the passphrase. */
class BackupKey(val key: ByteArray, val salt: ByteArray, val params: KdfParams)

/**
 * Backup file formats:
 *
 *  v2 (current): "MMBK" | 0x02 | kdf(1 = Argon2id) | memoryKiB(int32) | iterations(int32) | parallelism(1)
 *                | salt(16) | iv(12) | AES-256-GCM(ciphertext + 128-bit tag)
 *      The whole 43-byte header is authenticated as AAD, so KDF parameters can't be tampered with.
 *      Key = Argon2id(passphrase, salt, params) → 32 bytes.
 *
 *  v1 (legacy, decrypt only): "MMBK" | 0x01 | salt(16) | iv(12) | AES-256-GCM, key = PBKDF2-HMAC-SHA256 150k.
 *
 * The passphrase and the derived key are never written into the file.
 */
object BackupCrypto {
    private val MAGIC = "MMBK".toByteArray()
    private const val V1: Byte = 1
    private const val V2: Byte = 2
    private const val KDF_ARGON2ID: Byte = 1
    private const val V1_ITERATIONS = 150_000
    private const val V2_HEADER = 4 + 1 + 1 + 4 + 4 + 1 + 16 + 12
    private const val SALT = 16
    private const val IV = 12

    /** Minimum for one-off file backups. */
    const val MIN_PASSWORD = 8
    /** Minimum for the cloud recovery passphrase. */
    const val MIN_PASSPHRASE = 10

    private val random = SecureRandom()

    fun argon2id(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(params.memoryKiB).withIterations(params.iterations).withParallelism(params.parallelism)
                .build()
        )
        return ByteArray(32).also { gen.generateBytes(passphrase, it) }
    }

    /** Derives a new key with a fresh random salt. */
    fun newKey(passphrase: CharArray, params: KdfParams = KdfParams()): BackupKey {
        val salt = ByteArray(SALT).also(random::nextBytes)
        return BackupKey(argon2id(passphrase, salt, params), salt, params)
    }

    /** One-shot encryption with a password (local file export). */
    fun encrypt(plain: ByteArray, password: CharArray, params: KdfParams = KdfParams()): ByteArray {
        if (password.size < MIN_PASSWORD) throw BackupException("Use a password of at least $MIN_PASSWORD characters")
        return encryptWithKey(plain, newKey(password, params))
    }

    /** Encrypts with an already-derived key (used by scheduled backups, which can't ask for the passphrase). */
    fun encryptWithKey(plain: ByteArray, key: BackupKey): ByteArray {
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = ByteBuffer.allocate(V2_HEADER).apply {
            put(MAGIC); put(V2); put(KDF_ARGON2ID)
            putInt(key.params.memoryKiB); putInt(key.params.iterations); put(key.params.parallelism.toByte())
            put(key.salt); put(iv)
        }.array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    data class Header(val version: Int, val params: KdfParams?, val salt: ByteArray)

    fun readHeader(data: ByteArray): Header {
        if (data.size < 5 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException("This isn't a FinFox backup file")
        return when (data[4]) {
            V1 -> {
                if (data.size < 5 + SALT + IV + 16) throw BackupException("The backup file is truncated")
                Header(1, null, data.copyOfRange(5, 5 + SALT))
            }
            V2 -> {
                if (data.size < V2_HEADER + 16) throw BackupException("The backup file is truncated")
                val b = ByteBuffer.wrap(data, 5, V2_HEADER - 5)
                if (b.get() != KDF_ARGON2ID) throw BackupException("Unsupported key derivation in backup")
                val params = KdfParams(b.getInt(), b.getInt(), b.get().toInt())
                // Bound the parameters so a crafted file can't exhaust memory/CPU.
                if (!params.isSane()) throw BackupException("The backup file has invalid encryption parameters")
                Header(2, params, data.copyOfRange(V2_HEADER - IV - SALT, V2_HEADER - IV))
            }
            else -> throw BackupException("This backup was made by a newer version of FinFox")
        }
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        val h = readHeader(data)
        return if (h.version == 1) decryptV1(data, password) else decryptWithKey(data, argon2id(password, h.salt, h.params!!))
    }

    /** Decrypts a v2 file with a derived key. Fails with a clear message on a wrong key or tampering. */
    fun decryptWithKey(data: ByteArray, key: ByteArray): ByteArray {
        val h = readHeader(data)
        if (h.version != 2) throw BackupException("Unsupported backup version")
        val header = data.copyOfRange(0, V2_HEADER)
        val iv = data.copyOfRange(V2_HEADER - IV, V2_HEADER)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return try { c.doFinal(data, V2_HEADER, data.size - V2_HEADER) } catch (e: AEADBadTagException) {
            throw BackupException("Wrong recovery passphrase, or the backup file is damaged")
        }
    }

    private fun decryptV1(data: ByteArray, password: CharArray): ByteArray {
        val header = MAGIC.size + 1 + SALT + IV
        val salt = data.copyOfRange(5, 21)
        val iv = data.copyOfRange(21, 33)
        val spec = PBEKeySpec(password, salt, V1_ITERATIONS, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        spec.clearPassword()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + V1)
        return try { c.doFinal(data, header, data.size - header) } catch (e: AEADBadTagException) {
            throw BackupException("Wrong password or the file is damaged")
        }
    }

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun md5Hex(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
