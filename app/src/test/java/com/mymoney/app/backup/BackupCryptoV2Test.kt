package com.mymoney.app.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Argon2id + AES-256-GCM backup format (v2) and backward compatibility with v1 files. */
class BackupCryptoV2Test {
    private val fast = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val data = "{\"accounts\":[{\"name\":\"HDFC ₹\"}]}".toByteArray()

    @Test fun headerCarriesArgon2ParamsAndSaltButNotTheKey() {
        val key = BackupCrypto.newKey("correct horse battery".toCharArray(), fast)
        val enc = BackupCrypto.encryptWithKey(data, key)
        val h = BackupCrypto.readHeader(enc)
        assertThat(h.version).isEqualTo(2)
        assertThat(h.params).isEqualTo(fast)
        assertThat(h.salt).isEqualTo(key.salt)
        // Neither the key nor the plaintext appears in the file.
        assertThat(enc.toList().windowed(key.key.size).none { it == key.key.toList() }).isTrue()
        assertThat(String(enc, Charsets.ISO_8859_1)).doesNotContain("HDFC")
    }

    @Test fun anotherDeviceRestoresWithOnlyThePassphrase() {
        // Device A: background backup with the stored derived key.
        val keyA = BackupCrypto.newKey("correct horse battery".toCharArray(), fast)
        val file = BackupCrypto.encryptWithKey(data, keyA)
        // Device B: knows nothing but the passphrase.
        assertThat(BackupCrypto.decrypt(file, "correct horse battery".toCharArray())).isEqualTo(data)
    }

    @Test fun invalidPassphraseFailsCleanly() {
        val file = BackupCrypto.encryptWithKey(data, BackupCrypto.newKey("correct horse battery".toCharArray(), fast))
        val e = assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file, "correct horse batterY".toCharArray()) }
        assertThat(e.message).contains("Wrong recovery passphrase")
    }

    @Test fun corruptedOrTamperedFilesAreRejected() {
        val pass = "correct horse battery".toCharArray()
        val file = BackupCrypto.encryptWithKey(data, BackupCrypto.newKey(pass, fast))
        // Flipped ciphertext byte → GCM tag check fails.
        val body = file.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(body, pass) }
        // Header tampering (iterations changed) → authenticated as AAD, so decrypt fails.
        val hdr = file.copyOf().also { it[13] = (it[13] + 1).toByte() }
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(hdr, pass) }
        // Truncated file.
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(file.copyOf(30), pass) }
        // Unknown future version.
        val future = file.copyOf().also { it[4] = 9 }
        assertThat(assertThrows(BackupException::class.java) { BackupCrypto.decrypt(future, pass) }.message).contains("newer version")
    }

    @Test fun absurdKdfParametersInAFileAreRefused() {
        val file = BackupCrypto.encryptWithKey(data, BackupCrypto.newKey("correct horse battery".toCharArray(), fast))
        // Claim 2 GiB of memory: must be refused before any derivation happens.
        val evil = file.copyOf().also { it[6] = 0x00; it[7] = 0x20; it[8] = 0x00; it[9] = 0x00 }
        assertThat(assertThrows(BackupException::class.java) { BackupCrypto.decrypt(evil, "x".toCharArray()) }.message).contains("invalid encryption parameters")
    }

    @Test fun argon2idIsDeterministicForSameInputsAndSaltSensitive() {
        val salt = ByteArray(16) { it.toByte() }
        val a = BackupCrypto.argon2id("pass phrase 1".toCharArray(), salt, fast)
        val b = BackupCrypto.argon2id("pass phrase 1".toCharArray(), salt, fast)
        val c = BackupCrypto.argon2id("pass phrase 1".toCharArray(), ByteArray(16), fast)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a.size).isEqualTo(32)
    }

    @Test fun legacyV1FilesStillDecrypt() {
        // Build a v1 file exactly as version 1.0.0 of the app did (PBKDF2-HMAC-SHA256, 150k iterations).
        val magic = "MMBK".toByteArray()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec("old password".toCharArray(), salt, 150_000, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(magic + 1.toByte()) }
        val v1 = magic + byteArrayOf(1) + salt + iv + c.doFinal(data)
        assertThat(BackupCrypto.decrypt(v1, "old password".toCharArray())).isEqualTo(data)
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(v1, "wrong password".toCharArray()) }
    }
}
