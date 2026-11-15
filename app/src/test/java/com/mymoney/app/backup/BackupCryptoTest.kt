package com.mymoney.app.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test fun roundTrip() {
        val data = "{\"hello\":\"₹ world\"}".toByteArray()
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray())
        assertThat(enc.copyOfRange(0, 4).decodeToString()).isEqualTo("MMBK")
        assertThat(String(enc)).doesNotContain("hello")
        assertThat(BackupCrypto.decrypt(enc, "correct horse".toCharArray())).isEqualTo(data)
    }

    @Test fun wrongPasswordAndTamperingFail() {
        val enc = BackupCrypto.encrypt("secret".toByteArray(), "password123".toCharArray())
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(enc, "password124".toCharArray()) }
        val tampered = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt(tampered, "password123".toCharArray()) }
        assertThrows(BackupException::class.java) { BackupCrypto.decrypt("not a backup".toByteArray(), "password123".toCharArray()) }
    }

    @Test fun shortPasswordRejected() {
        assertThrows(BackupException::class.java) { BackupCrypto.encrypt(byteArrayOf(1), "short".toCharArray()) }
    }

    @Test fun eachBackupUsesFreshSaltAndIv() {
        val a = BackupCrypto.encrypt("x".toByteArray(), "password123".toCharArray())
        val b = BackupCrypto.encrypt("x".toByteArray(), "password123".toCharArray())
        assertThat(a).isNotEqualTo(b)
    }
}
