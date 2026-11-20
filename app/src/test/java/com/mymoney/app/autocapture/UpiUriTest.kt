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
        val out = u.toPayUri(12_000, "Milk", "FF123")
        assertThat(out).startsWith("upi://pay?pa=shop@ybl&pn=Corner%20Shop&mc=5411&am=120.00&cu=INR")
        assertThat(out).contains("tn=Milk")
    }

    @Test fun signedQrIsNotAltered() {
        val u = UpiUri.parse("upi://pay?pa=merchant@hdfc&pn=M&am=10.00&cu=INR&sign=ABC")!!
        assertThat(u.toPayUri(1_000, "note", "FF1")).isEqualTo("upi://pay?pa=merchant@hdfc&pn=M&am=10.00&cu=INR&sign=ABC")
    }

    @Test fun manualPayeeGetsReference() {
        val out = UpiUri.manual("friend@oksbi", "Friend").toPayUri(5_000, null, "FFREF")
        assertThat(out).isEqualTo("upi://pay?pa=friend@oksbi&pn=Friend&am=50.00&cu=INR&tr=FFREF")
    }

    @Test fun parsesUpiAppResponses() {
        assertThat(UpiResponse.parse("txnId=AXI123&responseCode=00&Status=SUCCESS&txnRef=FF1&ApprovalRefNo=631234567890").let { it.status to it.approvalRef })
            .isEqualTo(UpiResultStatus.SUCCESS to "631234567890")
        assertThat(UpiResponse.parse("Status=FAILURE&responseCode=ZD").status).isEqualTo(UpiResultStatus.FAILURE)
        assertThat(UpiResponse.parse(null).status).isEqualTo(UpiResultStatus.UNKNOWN)
        assertThat(UpiResponse.parse(null, mapOf("Status" to "Success")).status).isEqualTo(UpiResultStatus.SUCCESS)
    }
}
