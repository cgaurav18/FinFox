package com.mymoney.app.autocapture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Real-world message shapes from Indian banks and UPI apps (numbers changed). */
class TxnMessageParserTest {
    private fun p(s: String) = TxnMessageParser.parse(s)

    @Test fun hdfcUpiDebit() {
        val r = p("Sent Rs.250.00 From HDFC Bank A/C x4321 To SWIGGY On 19/11/26 Ref 631234567890 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(25_000)
        assertThat(r.accountLast4).isEqualTo("4321")
        assertThat(r.counterparty).isEqualTo("Swiggy")
        assertThat(r.refNo).isEqualTo("631234567890")
        assertThat(r.isCard).isFalse()
    }

    @Test fun hdfcUpiCreditWithVpa() {
        val r = p("Money Received - INR 1,500.00 in your HDFC Bank A/c xx4321 on 19-11-26 by A/c linked to VPA rahul.sharma@okaxis (UPI Ref No 631234567891).")!!
        assertThat(r.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(r.amountMinor).isEqualTo(150_000)
        assertThat(r.vpa).isEqualTo("rahul.sharma@okaxis")
        assertThat(r.counterparty).isEqualTo("Rahul Sharma")
        assertThat(r.refNo).isEqualTo("631234567891")
    }

    @Test fun sbiDebitWithoutCurrencySymbol() {
        val r = p("Dear UPI user A/C X9876 debited by 120.0 on date 19Nov26 trf to CHAI POINT Refno 631234567892. If not u? call 1800111109. -SBI")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(12_000)
        assertThat(r.accountLast4).isEqualTo("9876")
        assertThat(r.counterparty).isEqualTo("Chai Point")
        assertThat(r.refNo).isEqualTo("631234567892")
    }

    @Test fun iciciDebitNamesPayeeBeforeCredited() {
        val r = p("ICICI Bank Acct XX123 debited for Rs 499.00 on 19-Nov-26; ZOMATO LTD credited. UPI:631234567893. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(49_900)
        assertThat(r.accountLast4).isEqualTo("123")
        assertThat(r.counterparty).isEqualTo("Zomato Ltd")
        assertThat(r.refNo).isEqualTo("631234567893")
    }

    @Test fun creditCardSpend() {
        val r = p("Rs.1,249.00 spent on your SBI Credit Card ending 5678 at AMAZON on 19/11/26. Trxn. Ref No. 1234. Not you? Call 39020202")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(124_900)
        assertThat(r.isCard).isTrue()
        assertThat(r.accountLast4).isEqualTo("5678")
        assertThat(r.counterparty).isEqualTo("Amazon")
    }

    @Test fun balanceIsNotMistakenForAmount() {
        val r = p("Avl Bal: INR 52,000.00. INR 2,000.00 debited from A/c XX1111 on 19-11-26 for UPI to merchant@paytm")!!
        assertThat(r.amountMinor).isEqualTo(200_000)
        val r2 = p("Rs 300 debited from a/c XX1111 on 19-11. Avl Bal Rs 9,700.50")!!
        assertThat(r2.amountMinor).isEqualTo(30_000)
    }

    @Test fun upiAppNotifications() {
        val gpay = p("Paid ₹85 to Blue Tokai Coffee")!!
        assertThat(gpay.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(gpay.amountMinor).isEqualTo(8_500)
        assertThat(gpay.counterparty).isEqualTo("Blue Tokai Coffee")
        val phonepe = p("Received ₹500 from Anita Desai")!!
        assertThat(phonepe.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(phonepe.counterparty).isEqualTo("Anita Desai")
    }

    @Test fun cardBillPaymentIsFlagged() {
        val r = p("Payment of Rs 15,000.00 received towards your ICICI Bank Credit Card XX5678. Thank you.")!!
        assertThat(r.kind).isEqualTo(CaptureKind.CARD_BILL_PAYMENT)
    }

    @Test fun nonTransactionsAreRejected() {
        listOf(
            "123456 is your OTP for a transaction of Rs 500 at AMAZON. Do not share it with anyone.",
            "Rahul has requested Rs 200 from you on Google Pay. Pay now.",
            "Your a/c XX1234 will be debited with Rs 999 on 25-11-26 towards Netflix mandate.",
            "Your credit card bill of Rs 12,000 is due on 25-11-26. Minimum amount due Rs 600.",
            "Transaction of Rs 500 at SWIGGY failed due to insufficient balance.",
            "Get a pre-approved personal loan of upto Rs 5,00,000. Apply now!",
            "Hi, see you at 5?",
        ).forEach { assertThat(p(it)).isNull() }
    }

    @Test fun smsSenderTrust() {
        assertThat(TxnMessageParser.isTrustedSmsSender("AD-HDFCBK")).isTrue()
        assertThat(TxnMessageParser.isTrustedSmsSender("JM-SBIUPI-S")).isTrue()
        assertThat(TxnMessageParser.isTrustedSmsSender("+919876543210")).isFalse()
        assertThat(TxnMessageParser.isTrustedSmsSender(null)).isFalse()
    }

    @Test fun categoryGuesses() {
        assertThat(CategoryGuesser.guess("Swiggy Instamart", TxnDirection.DEBIT)).isEqualTo("Groceries")
        assertThat(CategoryGuesser.guess("zomato@hdfcbank", TxnDirection.DEBIT)).isEqualTo("Food")
        assertThat(CategoryGuesser.guess("UBER INDIA", TxnDirection.DEBIT)).isEqualTo("Transport")
        assertThat(CategoryGuesser.guess("Coca cola", TxnDirection.DEBIT)).isNull() // "ola" must not match inside "cola"
        assertThat(CategoryGuesser.guess("SALARY NOV ACME LTD", TxnDirection.CREDIT)).isEqualTo("Salary")
        assertThat(CategoryGuesser.merchantKey("Swiggy@ICICI", "Swiggy")).isEqualTo("swiggy@icici")
        assertThat(CategoryGuesser.merchantKey(null, "Chai  Point!")).isEqualTo("chai point")
    }

    @Test fun hdfcCreditCardThankYouFormat() {
        val r = p("Thank you for using your HDFC Bank Credit Card ending 5678 for Rs 1249.00 at AMAZON on 2026-11-19:10:15:22.")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(124_900)
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.accountLast4).isEqualTo("5678")
        assertThat(r.counterparty).isEqualTo("Amazon")
    }

    @Test fun iciciCardSpendNamesMerchantAfterDate() {
        val r = p("INR 1,249.00 spent using ICICI Bank Card XX5678 on 19-Nov-26 on AMAZON. Avl Limit: INR 98,751.00. If not you, call 1800 2662/SMS BLOCK 5678 to 9215676766")!!
        assertThat(r.amountMinor).isEqualTo(124_900)
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.counterparty).isEqualTo("Amazon")
    }

    @Test fun cardAlertEmail() {
        val r = p("Alert: Credit Card transaction. Dear Customer, Rs.1,249.00 has been debited from your HDFC Bank Credit Card ending 5678 towards AMAZON on 19 Nov, 2026 at 10:15:22.")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(124_900)
        assertThat(r.accountLast4).isEqualTo("5678")
        assertThat(r.counterparty).isEqualTo("Amazon")
    }

    @Test fun debitCardIsNotACreditCard() {
        val r = p("Rs.500.00 spent on your SBI Debit Card ending 1111 at DMART on 19/11/26")!!
        assertThat(r.isCard).isTrue()
        assertThat(r.isCreditCard).isFalse()
    }

    // ---- Kotak / Axis formats as received (Nov 2026) ----

    @Test fun kotakUpiSms() {
        val r = p("Sent Rs.30.00 from Kotak Bank A/c X6101 to Mr  KISHORBHAI BABUB on 16-11-26. UPI Ref 668690536074. Not done by you? Tap https://kotak.bank.in/KBANKT/Fraud")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(3_000)
        assertThat(r.accountLast4).isEqualTo("6101")
        assertThat(r.isCreditCard).isFalse()
        assertThat(r.counterparty).isEqualTo("Kishorbhai Babub")
        assertThat(r.refNo).isEqualTo("668690536074")
    }

    @Test fun kotakCreditCardUpiSms() {
        val r = p("INR 374 spent on Kotak Credit Card x4347 on 31-10-26 at UPI-K-630430585235-AVE. Avl limit INR 31345 Not you? SMS CCLOST 4347 to 5676788")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(37_400)
        assertThat(r.accountLast4).isEqualTo("4347")
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.refNo).isEqualTo("630430585235")
        assertThat(r.counterparty).isNull()
    }

    @Test fun kotakCreditCardBillPayment() {
        val r = p("Payment of INR 12194 is credited to your Kotak Bank Credit Card x4347 on 08-NOV-2026 . Available Credit limit is INR 42043.41.")!!
        assertThat(r.kind).isEqualTo(CaptureKind.CARD_BILL_PAYMENT)
        assertThat(r.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(r.amountMinor).isEqualTo(1_219_400)
        assertThat(r.accountLast4).isEqualTo("4347")
        assertThat(r.isCreditCard).isTrue()
    }

    @Test fun kotakCreditCardEmail() {
        val text = PaymentApps.emailText(
            "Transaction successful on your Kotak Credit Card x4347",
            "Transaction Successful. INR 799 spent at UPI-K-632384159673-NEX on 19/11/26 at 09:03:59 AM using your Kotak Credit Card x4347. " +
                "Your available credit limit is INR 36,918.91. Not you? Click here to block your card immediately or call 18004100* " +
                "To explore card offers, visit www.kotak.bank.in or download the new Kotak Mobile Bank App",
        )
        val r = p(text)!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(79_900)
        assertThat(r.accountLast4).isEqualTo("4347")
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.refNo).isEqualTo("632384159673")
        assertThat(r.counterparty).isNull()
    }

    @Test fun axisCreditCardSms() {
        val r = p("Spent INR 60\nusing Axis Bank Credit Card no.\nXX3392 on 20-11-26 21:41:50 IST at\nBHANUBEN BA.\nAvl limit: INR 18540. Not you? SMS BLOCK 3392 to +919951860002.")!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(6_000)
        assertThat(r.accountLast4).isEqualTo("3392")
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.counterparty).isEqualTo("Bhanuben Ba")
    }

    @Test fun axisCreditCardEmail() {
        val text = PaymentApps.emailText(
            "INR 60 spent on credit card no. XX3392",
            "CLICK HERE 20-11-2026 Dear Customer, Here's the summary of your Axis Bank Credit Card Transaction: Transaction Amount: INR 60 " +
                "Merchant Name: BHANUBEN BA Axis Bank Credit Card No. XX3392 Date & Time: 20-11-2026, 21:41:50 IST Available Limit*: INR 18540",
        )
        val r = p(text)!!
        assertThat(r.direction).isEqualTo(TxnDirection.DEBIT)
        assertThat(r.amountMinor).isEqualTo(6_000)
        assertThat(r.accountLast4).isEqualTo("3392")
        assertThat(r.isCreditCard).isTrue()
        assertThat(r.counterparty).isEqualTo("Bhanuben Ba")
    }

    @Test fun axisOtpsAreIgnored() {
        listOf(
            "954422 is the OTP to generate your UPI PIN. Valid for 1 minute. Do not share it with anyone. Not you? Call us on 18001035577 - Axis Bank",
            "552411 is the OTP for Axis Mobile. It is valid for 1 min. Do not share it with anyone. Message ID: v5ksTK1fJMM - Axis Bank",
        ).forEach { assertThat(p(it)).isNull() }
    }

    @Test fun bankDebitForCardBillIsABankDebit() {
        val r = p("Sent Rs.12194.00 from Kotak Bank A/c X6101 to KOTAK CREDIT CARD on 08-11-26. UPI Ref 631200000001.")!!
        assertThat(r.accountLast4).isEqualTo("6101")
        assertThat(r.isCreditCard).isFalse()
    }

    @Test fun bankSideOfCardBillIsFlagged() {
        assertThat(p("Sent Rs.12194.00 from Kotak Bank A/c X6101 to KOTAK CREDIT CARD on 08-11-26. UPI Ref 631200000001.")!!.kind).isEqualTo(CaptureKind.CARD_BILL_PAYMENT)
        assertThat(p("Rs 5000 debited from A/c XX1234 towards CC bill payment on 08-11-26")!!.kind).isEqualTo(CaptureKind.CARD_BILL_PAYMENT)
        // A card *spend* is not a bill payment, and neither is a normal UPI payment.
        assertThat(p("INR 374 spent on Kotak Credit Card x4347 on 31-10-26 at UPI-K-630430585235-AVE")!!.kind).isEqualTo(CaptureKind.NORMAL)
        assertThat(p("Sent Rs.30.00 from Kotak Bank A/c X6101 to Mr KISHORBHAI BABUB on 16-11-26. UPI Ref 668690536074.")!!.kind).isEqualTo(CaptureKind.NORMAL)
    }

    @Test fun refundsAndReversalsAreCredits() {
        assertThat(p("Rs 500 refunded to your Kotak Credit Card x4347 by AMAZON on 19-11-26")!!.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(p("INR 250 has been reversed to your Axis Bank Credit Card XX3392")!!.direction).isEqualTo(TxnDirection.CREDIT)
        assertThat(p("Rs.120 deducted from your A/c XX6101 towards SMS charges")!!.direction).isEqualTo(TxnDirection.DEBIT)
    }
}
