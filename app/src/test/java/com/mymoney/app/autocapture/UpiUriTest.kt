package com.mymoney.app.autocapture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpiUriTest {
    @Test fun parsesPersonalQr() {
        val u = UpiUri.parse("upi://pay?pa=rahul.sharma@okaxis&pn=Rahul%20Sharma&cu=INR")!!
        assertThat(u.pa).isEqualTo("rahul.sharma@okaxis")
        assertThat(u.pn).isEqualTo("Rahul Sharma")
        assertThat(u.amountMinor).isNull()
        assertThat(u.isMerchant).isFalse()
    }

    @Test fun parsesMerchantQrWithAmount() {
        val u = UpiUri.parse("UPI://pay?pa=bluetokai@icici&pn=BLUE+TOKAI&mc=5814&tr=ORD123&am=185.50&cu=INR")!!
        assertThat(u.isMerchant).isTrue()
        assertThat(u.amountMinor).isEqualTo(18_550)
    }

    @Test fun rejectsNonUpi() {
        assertThat(UpiUri.parse("https://example.com/pay?pa=a@b")).isNull()
        assertThat(UpiUri.parse("upi://pay?pn=NoVpa")).isNull()
        assertThat(UpiUri.parse("hello")).isNull()
    }

    @Test fun payUriAddsOpenAmountAndKeepsScannedParams() {
        val u = UpiUri.parse("upi://pay?pa=shop@ybl&pn=Corner%20Shop&mc=5411")!!
        val out = u.toPayUri(12_000, "Milk")
        // Merchant QR: only the open amount/currency are filled in; no note or reference is added.
        assertThat(out).isEqualTo("upi://pay?pa=shop@ybl&pn=Corner%20Shop&mc=5411&am=120.00&cu=INR")
    }

    @Test fun signedQrIsNotAltered() {
        val u = UpiUri.parse("upi://pay?pa=merchant@hdfc&pn=M&am=10.00&cu=INR&sign=ABC")!!
        assertThat(u.toPayUri(1_000, "note")).isEqualTo("upi://pay?pa=merchant@hdfc&pn=M&am=10.00&cu=INR&sign=ABC")
    }

    @Test fun manualPayeeLinkHasNoMerchantFields() {
        val out = UpiUri.manual("friend@oksbi", "Friend").toPayUri(5_000, null)
        assertThat(out).isEqualTo("upi://pay?pa=friend@oksbi&pn=Friend&am=50.00&cu=INR")
    }

    @Test fun personalPaymentKeepsNoteButNeverAddsTr() {
        val out = UpiUri.manual("friend@oksbi", "Friend").toPayUri(5_000, "Dinner")
        assertThat(out).isEqualTo("upi://pay?pa=friend@oksbi&pn=Friend&am=50.00&cu=INR&tn=Dinner")
        val scanned = UpiUri.parse("upi://pay?pa=rahul@okaxis&pn=Rahul&mc=0000")!!.toPayUri(10_000, "Rent")
        assertThat(scanned).doesNotContain("tr=")
        assertThat(scanned).contains("tn=Rent")
    }

    @Test fun parsesUpiAppResponses() {
        assertThat(UpiResponse.parse("txnId=AXI123&responseCode=00&Status=SUCCESS&txnRef=FF1&ApprovalRefNo=631234567890").let { it.status to it.approvalRef })
            .isEqualTo(UpiResultStatus.SUCCESS to "631234567890")
        assertThat(UpiResponse.parse("Status=FAILURE&responseCode=ZD").status).isEqualTo(UpiResultStatus.FAILURE)
        assertThat(UpiResponse.parse(null).status).isEqualTo(UpiResultStatus.UNKNOWN)
        assertThat(UpiResponse.parse(null, mapOf("Status" to "Success")).status).isEqualTo(UpiResultStatus.SUCCESS)
    }

    @Test fun recognisesShopQrsWithoutMerchantCode() {
        listOf(
            "upi://pay?pa=Q123456789@ybl&pn=PhonePeMerchant&mc=0000&mode=02&purpose=00",
            "upi://pay?pa=BHARATPE.9012345678@fbpe&pn=Sharma%20Stores&cu=INR",
            "upi://pay?pa=sharmastores.12@okbizaxis&pn=Sharma%20Stores&aid=uGICAgID",
            "upi://pay?pa=paytmqr2810050501011abcd@paytm&pn=Paytm%20Merchant&paytmqr=2810050501011ABCD",
            "upi://pay?pa=shop@icici&pn=Shop&tr=ORD991",
        ).forEach { assertThat(UpiUri.parse(it)!!.isMerchant).isTrue() }
    }

    @Test fun personalQrsAreNotMerchants() {
        listOf(
            "upi://pay?pa=rahul.sharma@okaxis&pn=Rahul%20Sharma&aid=uGICAgID",
            "upi://pay?pa=9876543210@ybl&pn=Rahul&mc=0000&mode=02&purpose=00",
            "upi://pay?pa=rahul@oksbi&pn=Rahul&mc=0000",
        ).forEach { assertThat(UpiUri.parse(it)!!.isMerchant).isFalse() }
    }

    @Test fun receiveQrPayloadIsAStandardUpiLink() {
        assertThat(UpiUri.receiveUri("gaurav@okaxis", "Gaurav C")).isEqualTo("upi://pay?pa=gaurav@okaxis&pn=Gaurav%20C&cu=INR")
        val withAmount = UpiUri.receiveUri("gaurav@okaxis", "Gaurav C", 25_050, "Dinner split")
        assertThat(withAmount).isEqualTo("upi://pay?pa=gaurav@okaxis&pn=Gaurav%20C&am=250.50&cu=INR&tn=Dinner%20split")
        // Round-trips through FinFox's own scanner parser.
        val parsed = UpiUri.parse(withAmount)!!
        assertThat(parsed.pa).isEqualTo("gaurav@okaxis")
        assertThat(parsed.amountMinor).isEqualTo(25_050)
    }
}
