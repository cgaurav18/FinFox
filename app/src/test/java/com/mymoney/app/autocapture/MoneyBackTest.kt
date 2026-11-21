package com.mymoney.app.autocapture

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AutoCapturePreferencesEntity
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.repo.AccountInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-world case: ₹1 paid to a friend from Scan & Pay, and the friend sends ₹1 back two minutes later.
 * Bank SMS, the Kotak app and Google Pay all report the credit. Expected: one expense and one income.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoneyBackTest {
    private lateinit var e: Env
    private lateinit var cap: CaptureRepository
    private var kotak = 0L
    private val t0 = Dates.nowMillis() - 3_600_000

    private val debitSms = "Sent Rs.1.00 from Kotak Bank A/c X6101 to Damani Khanjan Aksha on 21-11-26. UPI Ref 632598978305. Not done by you? Tap https://kotak.bank.in/KBANKT/Fraud"
    private val creditSms = "Received Rs.1.00 in your Kotak Bank AC 6101 from Damani Khanjan Aksha on 21-11-26.UPI Ref:632598978399"
    private val gpayCredit = "Damani Khanjan Akshaybhai paid you ₹1.00. Tap to view."
    private val kotakAppCredit = "₹1 received from Damani Khanjan Akshaybhai. Amount credited to XX6101. Check out details."

    private fun sms(body: String, at: Long) = RawMessage(CaptureRepository.SOURCE_SMS, "VM-KOTAKB-S", body, at)
    private fun gpay(body: String, at: Long) = RawMessage("com.google.android.apps.nbu.paisa.user", "Google Pay", body, at)
    private fun kotakApp(body: String, at: Long) = RawMessage("com.msf.kbank.mobile", "Kotak", body, at)
    private suspend fun txns() = e.db.backupDao().transactions()

    private suspend fun setUp(kotakLast4: String?) {
        e = Env.inMemory()
        e.seed()
        cap = CaptureRepository(e.db, e.txns, e.notifier)
        e.bank("HDFC")
        kotak = e.accounts.save(AccountInput(type = AccountType.BANK, name = "Kotak", bankName = "Kotak", last4 = kotakLast4, openingMinor = 100 * R, colorArgb = 0, iconKey = "bank"))
        cap.savePrefs(AutoCapturePreferencesEntity(enabled = true))
    }

    @After fun tearDown() { if (::e.isInitialized) e.db.close() }

    private suspend fun payFromScanAndPay() {
        cap.startPayment("damani@okaxis", "Damani", 1 * R, e.cat("Other"), kotak, null, "FF1", "com.google.android.apps.nbu.paisa.user")
    }

    private suspend fun assertOneExpenseOneIncome() {
        val all = txns()
        assertThat(all.map { it.type to it.amountMinor }).containsExactly(TxnType.EXPENSE to 1 * R, TxnType.INCOME to 1 * R)
        assertThat(all.all { it.accountId == kotak }).isTrue()
        assertThat(e.balance(kotak)).isEqualTo(100 * R)
    }

    @Test fun parserReadsPaidYouAsMoneyReceived() {
        val p = TxnMessageParser.parse(gpayCredit)!!
        assertThat(p.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(p.counterparty).isEqualTo("Damani Khanjan Akshaybhai")
        assertThat(TxnMessageParser.parse(kotakAppCredit)!!.accountLast4).isEqualTo("6101")
    }

    @Test fun notificationsBeforeSms() = runTest {
        setUp("6101"); payFromScanAndPay()
        cap.process(sms(debitSms, t0))
        cap.process(gpay(gpayCredit, t0 + 120_000))
        cap.process(kotakApp(kotakAppCredit, t0 + 121_000))
        cap.process(sms(creditSms, t0 + 125_000))
        assertOneExpenseOneIncome()
    }

    @Test fun smsBeforeNotifications() = runTest {
        setUp("6101"); payFromScanAndPay()
        cap.process(sms(debitSms, t0))
        cap.process(sms(creditSms, t0 + 120_000))
        cap.process(gpay(gpayCredit, t0 + 121_000))
        cap.process(kotakApp(kotakAppCredit, t0 + 122_000))
        assertOneExpenseOneIncome()
    }

    @Test fun kotakSavedWithoutDigits() = runTest {
        setUp(null); payFromScanAndPay()
        cap.process(sms(debitSms, t0))
        cap.process(gpay(gpayCredit, t0 + 120_000))
        cap.process(sms(creditSms, t0 + 125_000))
        cap.process(kotakApp(kotakAppCredit, t0 + 126_000))
        // No account has the digits 6101, so FinFox can't know where the ₹1 landed: it waits in the review
        // list. It must not be dropped, and must not cancel out the expense as a fake "transfer".
        assertThat(txns().map { it.type }).containsExactly(TxnType.EXPENSE)
        val pending = cap.observePending().first()
        assertThat(pending.map { it.direction to it.amountMinor }).containsExactly(TxnDirection.CREDIT.name to 1 * R)
    }
}
