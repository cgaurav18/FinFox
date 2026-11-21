package com.mymoney.app.autocapture

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.core.Dates
import com.mymoney.app.data.Env
import com.mymoney.app.data.R
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AutoCapturePreferencesEntity
import com.mymoney.app.data.db.CaptureStatus
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.db.UpiPaymentStatus
import com.mymoney.app.data.repo.AccountInput
import com.mymoney.app.data.repo.TxnInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRepositoryTest {
    private lateinit var e: Env
    private lateinit var cap: CaptureRepository
    private var hdfc = 0L
    private val t0 = Dates.nowMillis() - 3_600_000

    private fun sms(body: String, at: Long = t0) = RawMessage(CaptureRepository.SOURCE_SMS, "AD-HDFCBK", body, at)
    private fun gpay(body: String, at: Long = t0) = RawMessage("com.google.android.apps.nbu.paisa.user", "Google Pay", body, at)
    private suspend fun txns() = e.db.backupDao().transactions()

    @Before fun setUp() = runTest {
        e = Env.inMemory()
        e.seed()
        cap = CaptureRepository(e.db, e.txns, e.notifier)
        hdfc = e.accounts.save(AccountInput(type = AccountType.BANK, name = "HDFC Savings", bankName = "HDFC", last4 = "4321", openingMinor = 10_000 * R, colorArgb = 0, iconKey = "bank"))
        cap.savePrefs(AutoCapturePreferencesEntity(enabled = true))
    }

    @After fun tearDown() = e.db.close()

    @Test fun bankSmsBecomesCategorisedExpenseOnMatchingAccount() = runTest {
        e.bank("SBI") // a second bank account: the last 4 digits must decide
        val out = cap.process(sms("Sent Rs.250.00 From HDFC Bank A/C x4321 To SWIGGY On 19/11/26 Ref 631234567890"))
        assertThat(out).isEqualTo(CaptureOutcome.ADDED)
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.EXPENSE)
        assertThat(t.amountMinor).isEqualTo(250 * R)
        assertThat(t.accountId).isEqualTo(hdfc)
        assertThat(t.categoryId).isEqualTo(e.cat("Food"))
        assertThat(t.merchant).isEqualTo("Swiggy")
        assertThat(t.tags).isEqualTo("auto")
        assertThat(e.balance(hdfc)).isEqualTo(9_750 * R)
    }

    @Test fun samePaymentFromSmsAndUpiAppIsRecordedOnce() = runTest {
        cap.process(gpay("Paid ₹250 to Swiggy", t0))
        val second = cap.process(sms("Sent Rs.250.00 From HDFC Bank A/C x4321 To SWIGGY On 19/11/26 Ref 631234567890", t0 + 40_000))
        assertThat(second).isEqualTo(CaptureOutcome.DUPLICATE)
        assertThat(txns()).hasSize(1)
    }

    @Test fun repeatedNotificationUpdateIsIgnored() = runTest {
        cap.process(gpay("Paid ₹40 to Chai Point", t0))
        assertThat(cap.process(gpay("Paid ₹40 to Chai Point", t0 + 5_000))).isEqualTo(CaptureOutcome.DUPLICATE)
        assertThat(txns()).hasSize(1)
    }

    @Test fun twoRealPaymentsOfSameAmountFromSameSourceAreBothKept() = runTest {
        cap.process(sms("Sent Rs.20.00 From HDFC Bank A/C x4321 To CHAI POINT On 19/11/26 Ref 631234567001", t0))
        cap.process(sms("Sent Rs.20.00 From HDFC Bank A/C x4321 To CHAI POINT On 19/11/26 Ref 631234567002", t0 + 60_000))
        assertThat(txns()).hasSize(2)
    }

    @Test fun incomeIsRecognised() = runTest {
        cap.process(sms("Money Received - INR 50,000.00 in your HDFC Bank A/c xx4321 on 01-11-26 by NEFT SALARY ACME LTD"))
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.INCOME)
        assertThat(t.categoryId).isEqualTo(e.cat("Salary", CategoryType.INCOME))
    }

    @Test fun personalNumbersAndNonTransactionsAreIgnored() = runTest {
        assertThat(cap.process(RawMessage("sms", "+919876543210", "Sent Rs.500 from A/C x4321 to you", t0))).isEqualTo(CaptureOutcome.NOT_A_TRANSACTION)
        assertThat(cap.process(sms("123456 is your OTP for Rs 500 at AMAZON"))).isEqualTo(CaptureOutcome.NOT_A_TRANSACTION)
        assertThat(txns()).isEmpty()
    }

    @Test fun reviewModeParksEntriesAndApprovalLearnsTheCategory() = runTest {
        cap.savePrefs(cap.prefs().copy(autoAdd = false))
        cap.process(sms("Sent Rs.300.00 From HDFC Bank A/C x4321 To RAMESH STORES On 19/11/26 Ref 631234567003"))
        assertThat(txns()).isEmpty()
        val pending = cap.observePending().first().single()
        assertThat(pending.status).isEqualTo(CaptureStatus.PENDING)
        assertThat(pending.suggestedAccountId).isEqualTo(hdfc)

        val groceries = e.cat("Groceries")
        cap.approve(pending.id, hdfc, groceries, "Ramesh Stores")
        assertThat(txns().single().categoryId).isEqualTo(groceries)

        // Next time the same shop is categorised from the learned rule.
        cap.savePrefs(cap.prefs().copy(autoAdd = true))
        cap.process(sms("Sent Rs.120.00 From HDFC Bank A/C x4321 To RAMESH STORES On 20/11/26 Ref 631234567004", t0 + 86_400_000))
        assertThat(txns().map { it.categoryId }).containsExactly(groceries, groceries)
    }

    @Test fun unknownAccountGoesToReview() = runTest {
        e.bank("SBI") // two banks, no default, message names no account
        assertThat(cap.process(gpay("Paid ₹99 to Netflix"))).isEqualTo(CaptureOutcome.PENDING)
        cap.savePrefs(cap.prefs().copy(defaultAccountId = hdfc))
        assertThat(cap.process(gpay("Paid ₹199 to Spotify", t0 + 60_000))).isEqualTo(CaptureOutcome.ADDED)
        assertThat(txns().single().categoryId).isEqualTo(e.cat("Entertainment"))
    }

    @Test fun manualEntryIsNotDuplicated() = runTest {
        e.txns.save(TxnInput(type = TxnType.EXPENSE, amountMinor = 500 * R, accountId = hdfc, categoryId = e.cat("Shopping"), dateMillis = t0))
        assertThat(cap.process(sms("Sent Rs.500.00 From HDFC Bank A/C x4321 To DMART On 19/11/26 Ref 631234567005", t0 + 120_000)))
            .isEqualTo(CaptureOutcome.DUPLICATE)
        assertThat(txns()).hasSize(1)
    }

    @Test fun disabledDoesNothing() = runTest {
        cap.savePrefs(cap.prefs().copy(enabled = false))
        assertThat(cap.process(sms("Sent Rs.250.00 From HDFC Bank A/C x4321 To SWIGGY On 19/11/26 Ref 631234567890"))).isEqualTo(CaptureOutcome.DISABLED)
    }

    @Test fun scanAndPaySuccessIsRecordedOnceEvenWhenTheBankSmsFollows() = runTest {
        val travel = e.cat("Travel")
        val id = cap.startPayment("irctc@sbi", "IRCTC", 1_250 * R, travel, hdfc, null, "FF1", "com.phonepe.app")
        val txnId = cap.completePayment(id, "631234567006")
        assertThat(cap.completePayment(id, null)).isEqualTo(txnId) // idempotent
        val now = Dates.nowMillis()
        assertThat(cap.process(sms("Sent Rs.1250.00 From HDFC Bank A/C x4321 To IRCTC On 19/11/26 Ref 631234567006", now + 30_000)))
            .isEqualTo(CaptureOutcome.DUPLICATE)
        val t = txns().single()
        assertThat(t.categoryId).isEqualTo(travel)
        assertThat(t.merchant).isEqualTo("IRCTC")
    }

    @Test fun scanAndPayWithoutResultIsCompletedByTheBankSms() = runTest {
        val medical = e.cat("Medical")
        val id = cap.startPayment("dr.mehta@okhdfc", "Dr Mehta", 800 * R, medical, hdfc, null, "FF2", "com.google.android.apps.nbu.paisa.user")
        val out = cap.process(sms("Sent Rs.800.00 From HDFC Bank A/C x4321 To DR MEHTA On 19/11/26 Ref 631234567007", Dates.nowMillis() + 60_000))
        assertThat(out).isEqualTo(CaptureOutcome.ADDED)
        assertThat(txns().single().categoryId).isEqualTo(medical)
        assertThat(cap.payment(id)!!.status).isEqualTo(UpiPaymentStatus.MATCHED)
    }

    @Test fun editingAnAutoEntryTeachesTheCategory() = runTest {
        cap.process(gpay("Paid ₹60 to Sharma Ji", t0))
        val t = txns().single()
        val entertainment = e.cat("Entertainment")
        e.txns.save(TxnInput(id = t.id, type = t.type, amountMinor = t.amountMinor, accountId = t.accountId, categoryId = entertainment, dateMillis = t.dateMillis, merchant = t.merchant, tags = t.tags))
        cap.learnFromEdit(t.id)
        assertThat(cap.suggestCategory(null, "Sharma Ji")).isEqualTo(entertainment)
    }

    @Test fun moneyMovedBetweenOwnAccountsIsOneTransfer() = runTest {
        val sbi = e.accounts.save(AccountInput(type = AccountType.BANK, name = "SBI", bankName = "SBI", last4 = "9876", openingMinor = 0, colorArgb = 0, iconKey = "bank"))
        cap.process(sms("Sent Rs.5000.00 From HDFC Bank A/C x4321 To GAURAV On 19/11/26 Ref 631234567010"))
        cap.process(RawMessage("sms", "VM-SBIINB", "Dear Customer, your A/C X9876 credited by Rs.5000.00 on 19Nov26 by transfer from GAURAV. Ref 631234567011", t0 + 30_000))
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.TRANSFER)
        assertThat(t.accountId).isEqualTo(hdfc)
        assertThat(t.toAccountId).isEqualTo(sbi)
        assertThat(e.balance(hdfc)).isEqualTo(5_000 * R)
        assertThat(e.balance(sbi)).isEqualTo(5_000 * R)
    }

    @Test fun cardBillPaymentFromBankIsOneTransfer() = runTest {
        val card = e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "ICICI Card", bankName = "ICICI", last4 = "5678", openingMinor = 15_000 * R,
            creditLimitMinor = 100_000 * R, colorArgb = 0, iconKey = "credit_card"))
        assertThat(cap.process(RawMessage("sms", "AD-ICICIB", "Payment of Rs 15,000.00 received towards your ICICI Bank Credit Card XX5678. Thank you.", t0)))
            .isEqualTo(CaptureOutcome.PENDING)
        assertThat(cap.process(sms("Sent Rs.15000.00 From HDFC Bank A/C x4321 To CRED On 19/11/26 Ref 631234567012", t0 + 60_000)))
            .isEqualTo(CaptureOutcome.ADDED)
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.TRANSFER)
        assertThat(t.toAccountId).isEqualTo(card)
        assertThat(cap.observePending().first()).isEmpty()
        assertThat(e.db.accountDao().getWithBalance(card)!!.outstandingMinor).isEqualTo(0)
    }

    private fun gmail(sender: String, body: String, at: Long) = RawMessage("com.google.android.gm", sender, body, at)
    private suspend fun iciciCard() = e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "HDFC Card", bankName = "HDFC", last4 = "5678",
        openingMinor = 0, creditLimitMinor = 100_000 * R, colorArgb = 0, iconKey = "credit_card"))

    @Test fun cardSpendGoesOnTheCardAndTheAlertEmailAnHourLaterIsNotADuplicate() = runTest {
        val card = iciciCard()
        cap.process(RawMessage("sms", "AD-HDFCBK", "Thank you for using your HDFC Bank Credit Card ending 5678 for Rs 1249.00 at AMAZON on 2026-11-19:10:15:22.", t0))
        val email = cap.process(gmail("HDFC Bank InstaAlerts",
            "Alert: Credit Card transaction. Dear Customer, Rs.1,249.00 has been debited from your HDFC Bank Credit Card ending 5678 towards AMAZON on 19 Nov, 2026", t0 + 60 * 60_000))
        assertThat(email).isEqualTo(CaptureOutcome.DUPLICATE)
        val t = txns().single()
        assertThat(t.accountId).isEqualTo(card)
        assertThat(t.categoryId).isEqualTo(e.cat("Shopping"))
        assertThat(e.db.accountDao().getWithBalance(card)!!.outstandingMinor).isEqualTo(1_249 * R)
        assertThat(e.balance(hdfc)).isEqualTo(10_000 * R) // bank untouched
    }

    @Test fun emailArrivingFirstThenSmsIsRecordedOnce() = runTest {
        iciciCard()
        cap.process(gmail("HDFC Bank InstaAlerts", "Rs.799.00 has been debited from your HDFC Bank Credit Card ending 5678 towards ZOMATO on 19 Nov, 2026", t0))
        assertThat(cap.process(RawMessage("sms", "AD-HDFCBK", "Thank you for using your HDFC Bank Credit Card ending 5678 for Rs 799.00 at ZOMATO on 2026-11-19", t0 + 5 * 60_000)))
            .isEqualTo(CaptureOutcome.DUPLICATE)
        assertThat(txns()).hasSize(1)
    }

    @Test fun sameAmountOnDifferentCardsIsTwoPayments() = runTest {
        iciciCard()
        e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "SBI Card", bankName = "SBI", last4 = "9999", openingMinor = 0,
            creditLimitMinor = 100_000 * R, colorArgb = 0, iconKey = "credit_card"))
        cap.process(RawMessage("sms", "AD-HDFCBK", "Thank you for using your HDFC Bank Credit Card ending 5678 for Rs 500.00 at DMART on 2026-11-19", t0))
        cap.process(gmail("SBI Card Alerts", "Rs.500.00 spent on your SBI Credit Card ending 9999 at DMART on 19/11/26", t0 + 60_000))
        assertThat(txns()).hasSize(2)
    }

    @Test fun personalEmailsAreNeverRead() {
        assertThat(PaymentApps.looksLikeBankEmail("Rahul Sharma")).isFalse()
        assertThat(PaymentApps.looksLikeBankEmail("HDFC Bank InstaAlerts")).isTrue()
        assertThat(PaymentApps.looksLikeBankEmail("SBI Card")).isTrue()
    }

    @Test fun unknownCardSpendWaitsForReviewInsteadOfHittingTheBank() = runTest {
        // Two cards, neither has digits saved: don't guess, and never charge the bank account.
        repeat(2) { e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "Card $it", bankName = "Bank $it", openingMinor = 0, creditLimitMinor = 100_000 * R, colorArgb = 0, iconKey = "credit_card")) }
        cap.savePrefs(cap.prefs().copy(defaultAccountId = hdfc))
        assertThat(cap.process(sms("Rs.300.00 spent on your Axis Bank Credit Card ending 4444 at UBER on 19/11/26"))).isEqualTo(CaptureOutcome.PENDING)
        assertThat(txns()).isEmpty()
    }

    // ---- Kotak / Axis end to end ----

    private suspend fun kotakSetup(): Triple<Long, Long, Long> {
        val kotakBank = e.accounts.save(AccountInput(type = AccountType.BANK, name = "Kotak", bankName = "Kotak", last4 = "6101", openingMinor = 50_000 * R, colorArgb = 0, iconKey = "bank"))
        val kotakCard = e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "Kotak Card", bankName = "Kotak", last4 = "4347", openingMinor = 12_194 * R,
            creditLimitMinor = 50_000 * R, colorArgb = 0, iconKey = "credit_card"))
        val axisCard = e.accounts.save(AccountInput(type = AccountType.CREDIT_CARD, name = "Axis Card", bankName = "Axis", last4 = "3392", openingMinor = 0,
            creditLimitMinor = 20_000 * R, colorArgb = 0, iconKey = "credit_card"))
        return Triple(kotakBank, kotakCard, axisCard)
    }
    private fun outstanding(id: Long) = kotlinx.coroutines.runBlocking { e.db.accountDao().getWithBalance(id)!!.outstandingMinor }

    @Test fun axisSmsAndAxisEmailAreOneCardExpense() = runTest {
        val (_, _, axis) = kotakSetup()
        cap.process(RawMessage("sms", "JD-AXISBK-S", "Spent INR 60\nusing Axis Bank Credit Card no.\nXX3392 on 20-11-26 21:41:50 IST at\nBHANUBEN BA.\nAvl limit: INR 18540. Not you? SMS BLOCK 3392 to +919951860002.", t0))
        val email = PaymentApps.emailText("INR 60 spent on credit card no. XX3392",
            "CLICK HERE 20-11-2026 Dear Customer, Here's the summary of your Axis Bank Credit Card Transaction: Transaction Amount: INR 60 Merchant Name: BHANUBEN BA Axis Bank Credit Card No. XX3392")
        assertThat(cap.process(gmail("Axis Bank Alerts", email, t0 + 30_000))).isEqualTo(CaptureOutcome.DUPLICATE)
        val t = txns().single()
        assertThat(t.accountId).isEqualTo(axis)
        assertThat(t.merchant).isEqualTo("Bhanuben Ba")
        assertThat(outstanding(axis)).isEqualTo(60 * R)
    }

    @Test fun kotakCardSmsAndEmailAreOneExpense() = runTest {
        val (_, card, _) = kotakSetup()
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "INR 799 spent on Kotak Credit Card x4347 on 19-11-26 at UPI-K-632384159673-NEX. Avl limit INR 36918.91 Not you? SMS CCLOST 4347 to 5676788", t0))
        val email = PaymentApps.emailText("Transaction successful on your Kotak Credit Card x4347",
            "Transaction Successful. INR 799 spent at UPI-K-632384159673-NEX on 19/11/26 at 09:03:59 AM using your Kotak Credit Card x4347. Your available credit limit is INR 36,918.91.")
        assertThat(cap.process(gmail("creditcardalerts@kotak.bank.in", email, t0 + 60_000))).isEqualTo(CaptureOutcome.DUPLICATE)
        assertThat(txns().single().accountId).isEqualTo(card)
        assertThat(outstanding(card)).isEqualTo((12_194 + 799) * R)
    }

    @Test fun kotakUpiFromBankAccountGoesToTheBankAccount() = runTest {
        val (bank, _, _) = kotakSetup()
        cap.process(RawMessage("sms", "JD-KOTAKB-S", "Sent Rs.20.00 from Kotak Bank A/c X6101 to PRAVINBHAI JIVARAJBH on 19-11-26. UPI Ref 668919232563. Not done by you? Tap https://kotak.bank.in/KBANKT/Fraud", t0))
        val t = txns().single()
        assertThat(t.accountId).isEqualTo(bank)
        assertThat(t.merchant).isEqualTo("Pravinbhai Jivarajbh")
        assertThat(e.balance(bank)).isEqualTo((50_000 - 20) * R)
    }

    @Test fun cardBillPaidFromBankBecomesTransferEvenWhenTheCardCreditArrivesHoursLater() = runTest {
        val (bank, card, _) = kotakSetup()
        assertThat(cap.process(RawMessage("sms", "JD-KOTAKB-S", "Sent Rs.12194.00 from Kotak Bank A/c X6101 to KOTAK CREDIT CARD on 08-11-26. UPI Ref 631200000001.", t0)))
            .isEqualTo(CaptureOutcome.ADDED)
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "Payment of INR 12194 is credited to your Kotak Bank Credit Card x4347 on 08-NOV-2026 . Available Credit limit is INR 42043.41.", t0 + 5 * 60 * 60_000))
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.TRANSFER)
        assertThat(t.accountId).isEqualTo(bank)
        assertThat(t.toAccountId).isEqualTo(card)
        assertThat(outstanding(card)).isEqualTo(0)
        assertThat(cap.observePending().first()).isEmpty()
    }

    @Test fun bankSideCardBillIsATransferStraightAwayAndTheCardMessageJustConfirmsIt() = runTest {
        val (bank, card, _) = kotakSetup()
        cap.process(RawMessage("sms", "JD-KOTAKB-S", "Sent Rs.12194.00 from Kotak Bank A/c X6101 to KOTAK CREDIT CARD on 08-11-26. UPI Ref 631200000001.", t0))
        assertThat(txns().single().type).isEqualTo(TxnType.TRANSFER)
        assertThat(outstanding(card)).isEqualTo(0)
        assertThat(cap.process(RawMessage("sms", "AD-KOTAKB-S", "Payment of INR 12194 is credited to your Kotak Bank Credit Card x4347 on 08-NOV-2026 . Available Credit limit is INR 42043.41.", t0 + 60_000)))
            .isEqualTo(CaptureOutcome.ADDED)
        val t = txns().single()
        assertThat(t.accountId).isEqualTo(bank)
        assertThat(t.toAccountId).isEqualTo(card)
        assertThat(cap.observePending().first()).isEmpty()
    }

    @Test fun refundOnCreditCardIsRefundIncomeOnTheCard() = runTest {
        val (_, card, _) = kotakSetup()
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "INR 250 has been reversed to your Kotak Credit Card x4347 on 19-11-26", t0))
        val t = txns().single()
        assertThat(t.type).isEqualTo(TxnType.INCOME)
        assertThat(t.accountId).isEqualTo(card)
        assertThat(t.categoryId).isEqualTo(e.cat("Refund", CategoryType.INCOME))
        assertThat(outstanding(card)).isEqualTo((12_194 - 250) * R)
    }

    /** A realistic month: only real income and real spending count; transfers and duplicates don't. */
    @Test fun monthlyIncomeAndExpenseAreExact() = runTest {
        val (_, _, _) = kotakSetup()
        val m = 60_000L
        // Income: salary + a card refund
        cap.process(RawMessage("sms", "JD-KOTAKB-S", "Rs.50000.00 credited to Kotak Bank A/c X6101 on 01-11-26 by NEFT SALARY ACME LTD. UPI Ref 631200000101", t0))
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "INR 250 has been reversed to your Kotak Credit Card x4347 on 19-11-26", t0 + 1 * m))
        // Expenses: UPI from bank, card spend (SMS + email of the same), Axis card spend (SMS + email of the same)
        cap.process(RawMessage("sms", "JD-KOTAKB-S", "Sent Rs.30.00 from Kotak Bank A/c X6101 to Mr  KISHORBHAI BABUB on 16-11-26. UPI Ref 668690536074. Not done by you? Tap https://kotak.bank.in/KBANKT/Fraud", t0 + 2 * m))
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "INR 799 spent on Kotak Credit Card x4347 on 19-11-26 at UPI-K-632384159673-NEX. Avl limit INR 36918.91", t0 + 3 * m))
        cap.process(gmail("creditcardalerts@kotak.bank.in", PaymentApps.emailText("Transaction successful on your Kotak Credit Card x4347",
            "Transaction Successful. INR 799 spent at UPI-K-632384159673-NEX on 19/11/26 at 09:03:59 AM using your Kotak Credit Card x4347."), t0 + 4 * m))
        cap.process(RawMessage("sms", "JD-AXISBK-S", "Spent INR 60 using Axis Bank Credit Card no. XX3392 on 20-11-26 21:41:50 IST at BHANUBEN BA. Avl limit: INR 18540.", t0 + 5 * m))
        cap.process(gmail("Axis Bank Alerts", PaymentApps.emailText("INR 60 spent on credit card no. XX3392",
            "Transaction Amount: INR 60 Merchant Name: BHANUBEN BA Axis Bank Credit Card No. XX3392"), t0 + 6 * m))
        // Not income/expense: card bill from bank (both sides) and an OTP
        cap.process(RawMessage("sms", "JD-KOTAKB-S", "Sent Rs.12194.00 from Kotak Bank A/c X6101 to KOTAK CREDIT CARD on 08-11-26. UPI Ref 631200000001.", t0 + 7 * m))
        cap.process(RawMessage("sms", "AD-KOTAKB-S", "Payment of INR 12194 is credited to your Kotak Bank Credit Card x4347 on 08-NOV-2026 .", t0 + 8 * m))
        cap.process(RawMessage("sms", "JD-AXISBK-S", "954422 is the OTP to generate your UPI PIN. Valid for 1 minute. Do not share it with anyone. Not you? Call us on 18001035577 - Axis Bank", t0 + 9 * m))

        val day = Dates.toLocalDate(t0)
        val list = e.txns.detailsInRange(com.mymoney.app.core.DateRange(day.minusDays(1), day.plusDays(1)))
        val sum = com.mymoney.app.domain.Reports.summary(list)
        assertThat(sum.incomeMinor).isEqualTo((50_000 + 250) * R)
        assertThat(sum.expenseMinor).isEqualTo((30 + 799 + 60) * R)
        assertThat(list.count { it.txn.type == TxnType.TRANSFER }).isEqualTo(1)
        assertThat(list).hasSize(6)
    }
}
