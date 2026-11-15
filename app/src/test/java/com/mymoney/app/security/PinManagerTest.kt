package com.mymoney.app.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Stand-in for the Android Keystore (unavailable on the JVM): reversible XOR so seal != plain. */
class FakeBox : SecretBox {
    override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun open(sealed: ByteArray) = seal(sealed)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var now = 1_000_000L
    private lateinit var file: File
    private lateinit var pins: PinManager

    private fun create(): PinManager {
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        return PinManager(store, FakeBox(), iterations = 1_000, clock = { now })
    }

    @Before fun setUp() { file = File(tmp.root, "security.preferences_pb"); pins = create() }
    @After fun tearDown() { scope.cancel() }

    @Test fun createVerifyAndChangePin() = runTest {
        assertThat(pins.current().pinSet).isFalse()
        pins.setPin("4826")
        assertThat(pins.current().pinSet).isTrue()
        assertThat(pins.current().pinLength).isEqualTo(4)
        assertThat(pins.verify("4826")).isEqualTo(PinResult.Success)
        assertThat(pins.verify("1111")).isEqualTo(PinResult.Wrong(4))
        pins.setPin("482613")
        assertThat(pins.current().pinLength).isEqualTo(6)
        assertThat(pins.verify("4826")).isInstanceOf(PinResult.Wrong::class.java)
        assertThat(pins.verify("482613")).isEqualTo(PinResult.Success)
    }

    @Test fun pinIsNeverStoredInPlainText() = runTest {
        pins.setPin("482613")
        assertThat(file.readBytes().decodeToString()).doesNotContain("482613")
    }

    @Test fun lockoutAfterFiveFailuresThenRecovers() = runTest {
        pins.setPin("4826")
        repeat(4) { assertThat(pins.verify("0000")).isInstanceOf(PinResult.Wrong::class.java) }
        val r = pins.verify("0000")
        assertThat(r).isInstanceOf(PinResult.LockedOut::class.java)
        // Even the right PIN is refused during lockout.
        assertThat(pins.verify("4826")).isInstanceOf(PinResult.LockedOut::class.java)
        now += PinManager.BASE_LOCKOUT_MS + 1
        assertThat(pins.verify("4826")).isEqualTo(PinResult.Success)
    }

    @Test fun recoveryCodeResetsPin() = runTest {
        pins.setPin("4826")
        val code = pins.generateRecoveryCode()
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}")
        assertThat(pins.resetWithRecoveryCode("AAAA-AAAA-AAAA-AAAA", "9137")).isInstanceOf(PinResult.Wrong::class.java)
        assertThat(pins.resetWithRecoveryCode(code.lowercase().replace("-", " "), "9137")).isEqualTo(PinResult.Success)
        assertThat(pins.verify("9137")).isEqualTo(PinResult.Success)
    }

    @Test fun biometricOnboardingAndAutoLockSettingsPersist() = runTest {
        pins.setPin("4826"); pins.setBiometric(true); pins.setAutoLock(60); pins.completeOnboarding()
        val s = pins.current()
        assertThat(s.biometricEnabled).isTrue(); assertThat(s.autoLockSeconds).isEqualTo(60); assertThat(s.onboarded).isTrue()
        pins.clearAll()
        assertThat(pins.current().pinSet).isFalse()
        assertThat(pins.current().onboarded).isFalse()
    }

    @Test fun weakPinDetection() {
        assertThat(PinManager.isWeakPin("1111")).isTrue()
        assertThat(PinManager.isWeakPin("1234")).isTrue()
        assertThat(PinManager.isWeakPin("654321")).isTrue()
        assertThat(PinManager.isWeakPin("4826")).isFalse()
        assertThat(PinManager.isValidPin("12345")).isFalse()
        assertThat(PinManager.isValidPin("12a4")).isFalse()
    }
}
