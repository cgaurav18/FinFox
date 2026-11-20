package com.mymoney.app.autocapture

import java.math.BigDecimal

enum class TxnDirection { DEBIT, CREDIT }

/** What kind of money movement a message describes. */
enum class CaptureKind {
    /** Ordinary spend or receipt. */
    NORMAL,
    /** Money arriving on a credit card as a bill payment. Usually the other half of a transfer, so it is never auto-added. */
    CARD_BILL_PAYMENT,
}

data class ParsedTxn(
    val direction: TxnDirection,
    val amountMinor: Long,
    /** Last 3–4 digits of the bank account or card, when the message names one. */
    val accountLast4: String? = null,
    val isCard: Boolean = false,
    /** A credit card (not a debit card): the spend belongs on the card account, not the bank account. */
    val isCreditCard: Boolean = false,
    /** Merchant / person on the other side, cleaned up for display. */
    val counterparty: String? = null,
    /** UPI handle of the other side, e.g. "swiggy@icici". */
    val vpa: String? = null,
    /** UPI RRN / bank reference number, used to recognise the same payment reported twice. */
    val refNo: String? = null,
    val kind: CaptureKind = CaptureKind.NORMAL,
)

/**
 * Turns Indian bank SMS and UPI / bank app notification text into a [ParsedTxn].
 *
 * Deliberately conservative: anything that is not clearly a completed debit or credit (OTPs, payment
 * requests, reminders, offers, failed payments, future mandates) returns null, because a wrong
 * automatic entry is worse than a missed one.
 */
object TxnMessageParser {

    private val amountRegex = Regex("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    /** "debited by 500.00" (SBI style, no currency marker). */
    private val bareAmountRegex = Regex("""(?:debited|credited)\s+(?:by|for|with)\s+([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    private val debitWords = listOf(
        "debited", "debit of", "spent", "paid", "sent", "withdrawn", "withdrawal", "purchase", "payment of", "transferred to", "dr.", "debit",
        // Card alerts: "Thank you for using your Credit Card ending 5678 for Rs 499 at AMAZON", "charged Rs 199".
        "for using", "used at", "used for", "charged", "deducted",
    )
    private val creditWords = listOf("credited", "received", "deposited", "refund", "refunded", "reversed", "reversal", "cashback", "cr.", "added to")

    /** Phrases that mean "this is not a completed transaction". */
    private val rejectPatterns = listOf(
        Regex("""\botp\b"""), Regex("one time password"), Regex("verification code"),
        Regex("""\brequest(?:ed|ing)?\b"""), Regex("""\bcollect\b"""),
        Regex("""\bwill be (?:debited|credited|deducted|charged)\b"""), Regex("""\bto be (?:debited|credited)\b"""),
        Regex("""\bscheduled\b"""), Regex("""\bis due\b"""), Regex("""\bdue (?:on|date|by)\b"""), Regex("""\bminimum (?:amount )?due\b"""),
        Regex("""\breminder\b"""), Regex("""\bstatement\b"""),
        Regex("""\bfailed\b"""), Regex("""\bdeclined\b"""), Regex("""\bunsuccessful\b"""), Regex("""\binsufficient\b"""),
        Regex("""\boffer\b"""), Regex("""\bpre-?approved\b"""), Regex("""\beligible\b"""), Regex("""\bwon\b"""), Regex("""\bscratch\b"""),
        Regex("""\bapply now\b"""), Regex("""\bloan of upto\b"""),
    )

    /** Fraud-help footers ("Not you? SMS BLOCK 3392 to …", "Click here to block your card") are cut before parsing. */
    private val footerRegex = Regex(
        """\b(?:not you|not u|not done by you|if not (?:you|u|done by you)|sms block|sms cclost|click here to block|to block your card)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** UPI on credit cards: "at UPI-K-630430585235-AVE" carries the UPI reference (RRN). */
    private val upiCodeRefRegex = Regex("""\bupi-[a-z]{1,3}-([0-9]{9,16})\b""", RegexOption.IGNORE_CASE)
    private val honorific = Regex("""^(?:mr|mrs|ms|miss|shri|smt|m/s)\.?\s+""", RegexOption.IGNORE_CASE)
    /** A "name" that is really part of the account wording. */
    private val accountWords = Regex("""\bcard\b|\ba/c\b|\baccount\b|\bupi-[a-z]{1,3}-\d|\bavl\b|\blimit\b""", RegexOption.IGNORE_CASE)

    private val balanceContext = Regex("""(?:bal(?:ance)?|limit|avl|avail(?:able)?)[^0-9]{0,12}$""", RegexOption.IGNORE_CASE)

    private val accountRegex = Regex(
        """\b(?:a/c|ac|acct|account|card|a/c\s*no)\.?\s*(?:no\.?|number)?\s*(?:ending\s*(?:with|in)?\s*)?[x*]*\s*-?\s*([0-9]{3,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val vpaRegex = Regex("""\b([a-z0-9][a-z0-9._-]{1,63}@[a-z][a-z0-9]{1,30})\b""", RegexOption.IGNORE_CASE)
    private val refRegex = Regex(
        """(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn\s*id|transaction\s*id|upi)\s*(?:no\.?|number|id|#)?\s*[:.\-/ ]?\s*([0-9]{9,16})""",
        RegexOption.IGNORE_CASE,
    )

    /** Where the other party's name usually sits, in priority order. Group 1 is the name. */
    private val debitPartyPatterns = listOf(
        // Card alert emails: "Merchant Name: BHANUBEN BA Axis Bank Credit Card No. …"
        Regex("""\bmerchant(?:\s+name)?\s*:\s*(.+?)(?=\s+(?:[a-z]+\s+bank\b|axis\b|credit\s+card|debit\s+card|card\s+no|date\b|transaction\b|available\b|avl\b)|\.\s|,|$)""", RegexOption.IGNORE_CASE),
        Regex(""";\s*([^;]+?)\s+credited""", RegexOption.IGNORE_CASE),                       // ICICI: "...; SWIGGY credited."
        Regex("""\bat\s+(.+?)(?:\s+on\b|\s+via\b|\s+ref\b|\.\s|\.$|,|$)""", RegexOption.IGNORE_CASE), // cards: "at AMAZON on"
        Regex("""\btowards\s+(.+?)(?:\s+on\b|\s+via\b|\s+ref\b|\.\s|\.$|,|$)""", RegexOption.IGNORE_CASE), // card emails: "towards AMAZON on"
        Regex("""\b(?:trf|transfer(?:red)?|paid|sent|payment)\s+to\s+(.+?)(?:\s+on\b|\s+ref|\s+upi|\s+via\b|\.\s|\.$|,|\(|$)""", RegexOption.IGNORE_CASE),
        Regex("""\bto\s+(?:vpa\s+)?(.+?)(?:\s+on\b|\s+ref|\s+upi|\s+via\b|\.\s|\.$|,|\(|$)""", RegexOption.IGNORE_CASE),
        // ICICI cards: "spent using ICICI Bank Card XX5678 on 19-Nov-26 on AMAZON. Avl Limit..." (dates are skipped)
        Regex("""\bon\s+(?!\d)(.+?)(?:\s+avl\b|\s+via\b|\s+ref\b|\.\s|\.$|,|$)""", RegexOption.IGNORE_CASE),
    )
    private val creditPartyPatterns = listOf(
        Regex("""\b(?:received|credited)\b.*?\bfrom\s+(?:vpa\s+)?(.+?)(?:\s+on\b|\s+ref|\s+upi|\s+via\b|\.\s|\.$|,|\(|$)""", RegexOption.IGNORE_CASE),
        Regex("""\bby\s+(?:vpa\s+|a/c linked to vpa\s+)?(.+?)(?:\s+on\b|\s+ref|\s+upi|\s+via\b|\.\s|\.$|,|\(|$)""", RegexOption.IGNORE_CASE),
        Regex("""\bfrom\s+(?:vpa\s+)?(.+?)(?:\s+on\b|\s+ref|\s+upi|\s+via\b|\.\s|\.$|,|\(|$)""", RegexOption.IGNORE_CASE),
    )

    /** Words that show a captured "name" is really boilerplate, not a person or merchant. */
    private val junkParty = Regex(
        """^(?:your|you|using|credit|debit|block|call|a/c|ac|acct|account|bank|card|the|upi|imps|neft|rtgs|mob|mobile|beneficiary|vpa|xx|\*|[x*]+\d+)\b|^\d|@?\bbank\b$""",
        RegexOption.IGNORE_CASE,
    )

    /** Alphanumeric bank senders look like "AD-HDFCBK" / "VM-SBIUPI-S"; plain phone numbers are people (or fraud). */
    fun isTrustedSmsSender(sender: String?): Boolean {
        val s = sender?.trim().orEmpty()
        if (s.isEmpty()) return false
        val digits = s.count { it.isDigit() }
        return s.any { it.isLetter() } || digits in 1..6
    }

    fun parse(rawText: String): ParsedTxn? {
        val full = rawText.replace(Regex("""\s+"""), " ").trim()
        val text = footerRegex.find(full)?.takeIf { it.range.first >= 20 }?.let { full.substring(0, it.range.first).trim() } ?: full
        if (text.length < 12) return null
        val lower = text.lowercase()
        if (rejectPatterns.any { it.containsMatchIn(lower) }) return null

        // "Payment of Rs X received towards your credit card": money arriving on the card, whatever words come first.
        val cardBill = Regex("""credit\s*card""").containsMatchIn(lower) &&
            Regex("""payment (?:of .{1,30} )?(?:has been |is )?(?:received|credited)|received towards""").containsMatchIn(lower)
        val direction = if (cardBill) TxnDirection.CREDIT else direction(lower) ?: return null
        val amount = amount(text) ?: return null

        val isCard = Regex("""\b(?:credit|debit)\s+card\b|\bcard\s+(?:no\.?\s*)?(?:ending|x+|\*+)|\bon\s+(?:your\s+)?\w*\s*card\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            (Regex("""\bcard\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) && Regex("""\b(?:spent|purchase|used)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        // The account the money moved on decides: "from A/c X6101 to KOTAK CREDIT CARD" is a bank debit.
        val accMatch = accountRegex.find(text)
        val accIsBank = accMatch != null && !accMatch.value.contains("card", ignoreCase = true)
        val isCreditCard = !accIsBank && (Regex("""\bcredit\s*card\b""").containsMatchIn(lower) ||
            (isCard && !Regex("""\bdebit\s*card\b""").containsMatchIn(lower) && Regex("""\b(?:avl|available|avail)\.?\s*(?:credit\s*)?limit\b""").containsMatchIn(lower)))
        // The bank side of a card bill: "Sent Rs.12194 from A/c X6101 to KOTAK CREDIT CARD", "… to CRED", "CC bill payment".
        val bankSideBill = direction == TxnDirection.DEBIT && !isCreditCard && Regex(
            """\bto\s+(?:[a-z]+\s+){0,3}credit\s*card\b|\b(?:credit\s*card|cc)\s*(?:bill|payment|pymt|dues?)\b|\bcard\s*bill\b|\bto\s+cred\b|\bcred\s*club\b"""
        ).containsMatchIn(lower)
        val kind = if (cardBill || bankSideBill) CaptureKind.CARD_BILL_PAYMENT else CaptureKind.NORMAL

        val vpa = vpaRegex.findAll(text).map { it.groupValues[1] }.firstOrNull { !it.contains("bank", ignoreCase = true) || it.contains("@") }
        val party = counterparty(text, direction) ?: vpa?.let { prettyVpa(it) }

        return ParsedTxn(
            direction = direction,
            amountMinor = amount,
            accountLast4 = accMatch?.groupValues?.get(1),
            isCard = isCard,
            isCreditCard = isCreditCard,
            counterparty = party,
            vpa = vpa?.lowercase(),
            refNo = (refRegex.find(text) ?: upiCodeRefRegex.find(text))?.groupValues?.get(1),
            kind = kind,
        )
    }

    /** The earliest debit/credit keyword decides, e.g. "debited from A/c ... credited to VPA x" is a debit. */
    private fun direction(lower: String): TxnDirection? {
        fun firstIndex(words: List<String>) = words.mapNotNull { w ->
            Regex("""(?<![a-z])${Regex.escape(w)}(?![a-z])""").find(lower)?.range?.first
        }.minOrNull()
        val d = firstIndex(debitWords)
        val c = firstIndex(creditWords)
        return when {
            d == null && c == null -> null
            c == null -> TxnDirection.DEBIT
            d == null -> TxnDirection.CREDIT
            d <= c -> TxnDirection.DEBIT
            else -> TxnDirection.CREDIT
        }
    }

    /** First amount that is not an "Avl Bal" / "limit" figure. */
    private fun amount(text: String): Long? {
        val candidates = amountRegex.findAll(text).filter { m ->
            !balanceContext.containsMatchIn(text.substring(maxOf(0, m.range.first - 24), m.range.first))
        }.map { it.groupValues[1] } + bareAmountRegex.findAll(text).map { it.groupValues[1] }
        return candidates.firstNotNullOfOrNull(::toMinor)
    }

    private fun toMinor(s: String): Long? = runCatching {
        val v = BigDecimal(s.replace(",", "")).movePointRight(2).longValueExact()
        v.takeIf { it > 0 }
    }.getOrNull()

    private fun counterparty(text: String, direction: TxnDirection): String? {
        val patterns = if (direction == TxnDirection.DEBIT) debitPartyPatterns else creditPartyPatterns
        for (p in patterns) {
            for (m in p.findAll(text)) {
                val name = clean(m.groupValues[1]) ?: continue
                return name
            }
        }
        return null
    }

    private fun clean(raw: String): String? {
        var s = raw.trim().trim('.', ',', ';', ':', '-', '(', ')', '"', '\'').trim()
        // A bare VPA is shown as its readable handle.
        vpaRegex.matchEntire(s)?.let { return prettyVpa(it.groupValues[1]) }
        s = s.replace(Regex("""\s+"""), " ").replace(honorific, "")
        if (accountWords.containsMatchIn(s)) return null
        if (s.length < 2 || s.length > 48) return null
        if (junkParty.containsMatchIn(s)) return null
        if (s.count { it.isDigit() } > s.length / 2) return null
        return titleCase(s)
    }

    private fun prettyVpa(vpa: String): String {
        val handle = vpa.substringBefore('@')
        // Phone-number handles ("9876543210@ybl") are kept as is; names get tidied.
        if (handle.all { it.isDigit() }) return vpa.lowercase()
        return titleCase(handle.replace(Regex("""[._-]+"""), " ").replace(Regex("""\d+$"""), "").trim().ifEmpty { handle })
    }

    /** "SWIGGY LIMITED" → "Swiggy Limited"; mixed case input is left alone. */
    private fun titleCase(s: String): String =
        if (s.any { it.isLowerCase() } && s.any { it.isUpperCase() }) s
        else s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
}
