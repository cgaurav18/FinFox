package com.mymoney.app.autocapture

import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A UPI payment link as printed in QR codes (NPCI "upi://pay?pa=…&pn=…&am=…").
 *
 * Merchant QR codes can carry a signature over their parameters, so [toPayUri] keeps the scanned
 * parameters exactly as they were and only appends the amount (and currency) when the QR left
 * them open.
 */
data class UpiUri(
    /** Payee VPA (UPI ID). */
    val pa: String,
    /** Payee name. */
    val pn: String? = null,
    /** Fixed amount in minor units, when the QR specifies one. */
    val amountMinor: Long? = null,
    /** Transaction note. */
    val tn: String? = null,
    /** Merchant category code: present means a merchant QR. */
    val mc: String? = null,
    /** All parameters in their original order, raw (still URL-encoded). */
    val rawParams: List<Pair<String, String>> = emptyList(),
) {
    /**
     * A shop / business QR. UPI apps accept payments started by another app only for merchants, so this decides
     * whether FinFox can hand the payment straight to the UPI app. Many shop QRs carry no merchant code
     * (mc=0000 or none), so known merchant VPAs and merchant-only QR fields count too.
     */
    val isMerchant: Boolean get() {
        if (!mc.isNullOrBlank() && mc.trim('0').isNotEmpty()) return true
        val keys = rawParams.map { it.first.lowercase() }.toSet()
        if (keys.any { it in merchantKeys }) return true
        val handle = pa.substringBefore('@').lowercase()
        val psp = pa.substringAfter('@').lowercase()
        return psp.startsWith("okbiz") || psp in merchantPsps ||
            merchantHandles.any { it.containsMatchIn(handle) }
    }
    val displayName: String get() = pn?.takeIf { it.isNotBlank() } ?: pa

    /**
     * The URI handed to the UPI app. [amountMinor] is used only when the QR didn't fix an amount.
     *
     * Kept deliberately minimal: UPI apps (GPay, PhonePe, bank apps) refuse intent payments with
     * "payment not allowed" when a person-to-person link carries merchant-only fields such as `tr`,
     * or when a merchant QR has been altered. So we never add `tr`, and add a note only to plain
     * personal links. Our own reference is kept locally and isn't needed by the UPI app.
     */
    fun toPayUri(amountMinor: Long, note: String?): String {
        val params = rawParams.ifEmpty { listOfNotNull("pa" to enc(pa).replace("%40", "@"), pn?.let { "pn" to enc(it) }) }.toMutableList()
        val keys = params.map { it.first.lowercase() }.toSet()
        if ("am" !in keys) params += "am" to formatAmount(amountMinor)
        if ("cu" !in keys) params += "cu" to "INR"
        if ("sign" !in keys && !isMerchant && "tn" !in keys && !note.isNullOrBlank()) {
            params += "tn" to enc(note.trim().take(50))
        }
        return "upi://pay?" + params.joinToString("&") { "${it.first}=${it.second}" }
    }

    companion object {
        /** QR fields only merchants (or their acquirers) put in: order ref, signature, store/terminal ids. */
        private val merchantKeys = setOf("tr", "sign", "mid", "msid", "mtid", "paytmqr", "qrmedium", "gstin", "invoiceno", "invoicedate")
        /** Handles used only for business accounts: BharatPe, Google Pay for Business ("okbiz…" above). */
        private val merchantPsps = setOf("fbpe", "bharatpe")
        private val merchantHandles = listOf(
            Regex("""^paytmqr"""),           // Paytm soundbox / shop QR: paytmqr281005050101xxxx@paytm
            Regex("""^q\d{6,}$"""),          // PhonePe business QR: Q123456789@ybl
            Regex("""^bharatpe"""),          // BHARATPE.90xxxx@fbpe / @icici
        )
        private val vpaRegex = Regex("""^[a-zA-Z0-9][a-zA-Z0-9._-]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63}$""")

        fun isValidVpa(vpa: String) = vpaRegex.matches(vpa.trim())

        /** Parses a scanned QR payload. Returns null for anything that isn't a UPI pay link. */
        fun parse(payload: String): UpiUri? {
            val s = payload.trim()
            val schemeEnd = s.indexOf("://")
            if (schemeEnd < 0) return null
            val scheme = s.substring(0, schemeEnd).lowercase()
            if (scheme != "upi") return null
            val q = s.indexOf('?')
            if (q < 0) return null
            val raw = s.substring(q + 1).split('&').filter { it.isNotBlank() }.map {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to it.substring(i + 1)
            }
            fun get(k: String) = raw.firstOrNull { it.first.equals(k, ignoreCase = true) }?.second?.let(::dec)?.trim()?.takeIf { it.isNotEmpty() }
            val pa = get("pa") ?: return null
            if (!isValidVpa(pa)) return null
            return UpiUri(
                pa = pa, pn = get("pn"), amountMinor = get("am")?.let(::parseAmount), tn = get("tn"), mc = get("mc"),
                rawParams = raw,
            )
        }

        /**
         * The "Receive money" QR payload for the user's own UPI ID: payer apps open it with the payee filled in,
         * and with the amount fixed when [amountMinor] is given.
         */
        fun receiveUri(vpa: String, name: String, amountMinor: Long? = null, note: String? = null): String {
            val params = mutableListOf("pa" to enc(vpa.trim()).replace("%40", "@"), "pn" to enc(name.trim()))
            amountMinor?.takeIf { it > 0 }?.let { params += "am" to formatAmount(it) }
            params += "cu" to "INR"
            note?.trim()?.takeIf { it.isNotEmpty() }?.let { params += "tn" to enc(it.take(50)) }
            return "upi://pay?" + params.joinToString("&") { "${it.first}=${it.second}" }
        }

        fun manual(vpa: String, name: String?): UpiUri = UpiUri(pa = vpa.trim(), pn = name?.trim()?.takeIf { it.isNotEmpty() })

        fun formatAmount(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

        private fun parseAmount(s: String): Long? =
            runCatching { BigDecimal(s).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }

        private fun dec(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Outcome reported back by the UPI app (NPCI intent spec: "Status=SUCCESS&txnRef=…&ApprovalRefNo=…"). */
enum class UpiResultStatus { SUCCESS, SUBMITTED, FAILURE, UNKNOWN }

data class UpiResult(val status: UpiResultStatus, val approvalRef: String? = null, val txnId: String? = null)

object UpiResponse {
    /** Parses the "response" extra; apps vary in key case and separators. */
    fun parse(response: String?, extras: Map<String, String?> = emptyMap()): UpiResult {
        val map = mutableMapOf<String, String>()
        response?.split('&', ';')?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i).trim().lowercase()] = part.substring(i + 1).trim()
        }
        extras.forEach { (k, v) -> if (v != null && k.lowercase() !in map) map[k.lowercase()] = v }
        val status = when (map["status"]?.uppercase()) {
            "SUCCESS", "SUCCESSFUL", "S" -> UpiResultStatus.SUCCESS
            "SUBMITTED", "PENDING" -> UpiResultStatus.SUBMITTED
            "FAILURE", "FAILED", "F", "FAIL" -> UpiResultStatus.FAILURE
            else -> if (map["responsecode"] == "00" && map["txnid"] != null) UpiResultStatus.SUCCESS else UpiResultStatus.UNKNOWN
        }
        return UpiResult(status, map["approvalrefno"]?.takeIf { it.isNotBlank() } ?: map["approvalref"], map["txnid"])
    }
}
