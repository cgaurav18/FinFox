package com.mymoney.app.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Backup file format:
 *   "MMBK" | version(1) | salt(16) | iv(12) | AES-256-GCM(ciphertext + tag)
 * Key = PBKDF2-HMAC-SHA256(password, salt, 150k iterations). The password is never stored.
 */
object BackupCrypto {
    private val MAGIC = "MMBK".toByteArray()
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 150_000
    const val MIN_PASSWORD = 8

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        if (password.size < MIN_PASSWORD) throw BackupException("Use a password of at least $MIN_PASSWORD characters")
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + VERSION)
        return MAGIC + byteArrayOf(VERSION) + salt + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        val header = MAGIC.size + 1 + 16 + 12
        if (data.size < header + 16 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException("This isn't a My Money backup file")
        if (data[4] != VERSION) throw BackupException("Unsupported backup version")
        val salt = data.copyOfRange(5, 21)
        val iv = data.copyOfRange(21, 33)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + VERSION)
        return try { c.doFinal(data, header, data.size - header) } catch (e: AEADBadTagException) {
            throw BackupException("Wrong password or the file is damaged")
        }
    }
}
