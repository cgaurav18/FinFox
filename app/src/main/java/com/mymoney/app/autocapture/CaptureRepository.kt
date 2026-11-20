package com.mymoney.app.autocapture

import androidx.room.withTransaction
import com.mymoney.app.core.Dates
import com.mymoney.app.core.FinanceException
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.AutoCapturePreferencesEntity
import com.mymoney.app.data.db.CaptureStatus
import com.mymoney.app.data.db.CapturedMessageEntity
import com.mymoney.app.data.db.CategoryType
import com.mymoney.app.data.db.MerchantRuleEntity
import com.mymoney.app.data.db.SettingsEntity
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.data.db.UpiPaymentEntity
import com.mymoney.app.data.db.UpiPaymentStatus
import com.mymoney.app.data.repo.TransactionRepository
import com.mymoney.app.data.repo.TxnInput
import com.mymoney.app.notifications.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A message from any source, before parsing. */
data class RawMessage(
    /** "sms" or the posting app's package name. */
    val source: String,
    val sender: String?,
    val body: String,
    val timestampMillis: Long,
)

enum class CaptureOutcome { ADDED, PENDING, DUPLICATE, NOT_A_TRANSACTION, DISABLED }

/**
 * Turns bank SMS and payment-app notifications into ledger entries.
 *
 * Every source funnels into [process]: parse → drop duplicates (the same payment usually arrives as a
 * bank SMS *and* a UPI app notification) → pick account and category → add, or park for review.
 */
@Singleton
class CaptureRepository @Inject constructor(
    private val db: AppDatabase,
    private val txns: TransactionRepository,
    private val notifier: Notifier,
) {
    private val dao = db.captureDao()
    /** SMS and notification callbacks can race for the same payment; one at a time keeps dedupe exact. */
    private val lock = Mutex()

    companion object {
        const val SOURCE_SMS = "sms"
        const val SOURCE_FINFOX_PAY = "finfox-pay"
        const val AUTO_TAG = "auto"
        /** The same payment reported by two sources lands within this window. */
        const val DUPLICATE_WINDOW_MS = 10 * 60_000L
        /** Identical text within this window is the same notification being updated. */
        const val SAME_TEXT_WINDOW_MS = 30 * 60_000L
        /** Bank alert emails can lag the SMS by a while; an email is matched to an SMS/notification within this time. */
        const val EMAIL_DUPLICATE_WINDOW_MS = 3 * 60 * 60_000L
        /** Bank debit ↔ "payment credited to your credit card" are paired into one transfer within this time. */
        const val CARD_BILL_WINDOW_MS = 48 * 60 * 60_000L

        /** "4321" matches "x4321" / "321": banks print 3 or 4 digits. */
        fun last4Match(a: String, b: String) = a.endsWith(b) || b.endsWith(a)
        /** A Scan & Pay payment is matched to a bank message arriving within this time. */
        const val PAYMENT_MATCH_WINDOW_MS = 45 * 60_000L

        fun hash(text: String): String {
            val norm = text.lowercase().replace(Regex("""\s+"""), " ").trim()
            return MessageDigest.getInstance("SHA-256").digest(norm.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    fun observePrefs(): Flow<AutoCapturePreferencesEntity> = dao.observePrefs().map { it ?: AutoCapturePreferencesEntity() }
    suspend fun prefs(): AutoCapturePreferencesEntity = dao.getPrefs() ?: AutoCapturePreferencesEntity()
    suspend fun savePrefs(p: AutoCapturePreferencesEntity) = dao.upsertPrefs(p.copy(id = 1))
    fun observePending() = dao.observePending()
    fun observePendingCount() = dao.observePendingCount()
    fun observeRecent(limit: Int = 50) = dao.observeRecent(limit)
    suspend fun clearHistory() = dao.clearHistory()

    /**
     * Entry point for every live source. [forceReview] parks the entry for review regardless of the
     * auto-add setting (used for inbox imports, which may overlap entries the user typed in earlier).
     */
    suspend fun process(msg: RawMessage, forceReview: Boolean = false, notify: Boolean = true): CaptureOutcome = lock.withLock {
        val prefs = prefs()
        if (!prefs.enabled) return CaptureOutcome.DISABLED
        if (msg.source == SOURCE_SMS && !TxnMessageParser.isTrustedSmsSender(msg.sender)) return CaptureOutcome.NOT_A_TRANSACTION
        val parsed = TxnMessageParser.parse(msg.body) ?: return CaptureOutcome.NOT_A_TRANSACTION
        val textHash = hash(msg.body)
        if (isDuplicate(msg, parsed, textHash)) return CaptureOutcome.DUPLICATE

        val type = if (parsed.direction == TxnDirection.DEBIT) TxnType.EXPENSE else TxnType.INCOME
        // Already typed in by hand (outside FinFox's own capture) a few minutes ago.
        if (dao.similarTransactions(type.name, parsed.amountMinor, msg.timestampMillis - DUPLICATE_WINDOW_MS, msg.timestampMillis + DUPLICATE_WINDOW_MS) > 0 &&
            dao.around(msg.timestampMillis - DUPLICATE_WINDOW_MS, msg.timestampMillis + DUPLICATE_WINDOW_MS).none { it.transactionId != null && it.amountMinor == parsed.amountMinor }
        ) return CaptureOutcome.DUPLICATE

        // A payment the user started from Scan & Pay: use the category / account chosen there.
        val payment = if (parsed.direction == TxnDirection.DEBIT) dao.openPayment(parsed.amountMinor, msg.timestampMillis - PAYMENT_MATCH_WINDOW_MS) else null

        val accountId = resolveAccount(parsed, payment?.accountId, prefs)
        val categoryId = payment?.categoryId ?: resolveCategory(parsed, msg.body)
        val counterparty = payment?.payeeName ?: parsed.counterparty

        val base = CapturedMessageEntity(
            source = msg.source, sender = msg.sender, body = msg.body.take(1000), textHash = textHash, receivedAt = msg.timestampMillis,
            direction = parsed.direction.name, amountMinor = parsed.amountMinor, accountLast4 = parsed.accountLast4, isCard = parsed.isCard,
            counterparty = counterparty, vpa = payment?.vpa ?: parsed.vpa, refNo = parsed.refNo, kind = parsed.kind.name,
            status = CaptureStatus.PENDING, suggestedAccountId = accountId, suggestedCategoryId = categoryId, transactionId = null,
            createdAt = Dates.nowMillis(),
        )

        // Money moving between two of the user's own accounts (or paying a card bill) arrives as a debit SMS
        // and a credit SMS: that's one transfer, not an expense plus an income.
        if (!forceReview) pairAsTransfer(base, parsed, accountId)?.let { txnId ->
            dao.insert(base.copy(status = CaptureStatus.ADDED, transactionId = txnId, suggestedCategoryId = null))
            return CaptureOutcome.ADDED
        }

        // Card bill paid from the bank: a transfer to the card, never an expense (the card spends were the expenses).
        if (parsed.kind == CaptureKind.CARD_BILL_PAYMENT && parsed.direction == TxnDirection.DEBIT && !forceReview && prefs.autoAdd && accountId != null) {
            val card = billCard(msg.body)
            if (card != null && card != accountId) {
                val id = runCatching {
                    txns.save(
                        TxnInput(
                            type = TxnType.TRANSFER, amountMinor = parsed.amountMinor, accountId = accountId, toAccountId = card,
                            dateMillis = msg.timestampMillis, note = "Credit card bill payment (auto-captured)", tags = AUTO_TAG,
                        )
                    )
                }.getOrElse { if (it is FinanceException) null else throw it }
                if (id != null) {
                    dao.insert(base.copy(status = CaptureStatus.ADDED, transactionId = id, suggestedCategoryId = null))
                    return CaptureOutcome.ADDED
                }
            }
        }

        val canAutoAdd = !forceReview && (prefs.autoAdd || payment != null) && accountId != null && categoryId != null && parsed.kind == CaptureKind.NORMAL
        val txnId = if (canAutoAdd) runCatching {
            txns.save(
                TxnInput(
                    type = type, amountMinor = parsed.amountMinor, accountId = accountId, categoryId = categoryId,
                    dateMillis = msg.timestampMillis, merchant = counterparty?.take(60), note = noteFor(msg.source, payment != null), tags = AUTO_TAG,
                )
            )
        }.getOrElse { if (it is FinanceException) null else throw it } else null

        val captureId = dao.insert(base.copy(status = if (txnId != null) CaptureStatus.ADDED else CaptureStatus.PENDING, transactionId = txnId))
        if (payment != null && txnId != null) {
            dao.updatePayment(payment.copy(status = UpiPaymentStatus.MATCHED, transactionId = txnId, updatedAt = Dates.nowMillis()))
        }
        if (notify && prefs.notifyOnCapture) {
            val catName = categoryId?.let { db.categoryDao().getById(it)?.name }
            notifier.paymentCaptured(captureId, txnId, parsed.direction == TxnDirection.DEBIT, parsed.amountMinor, counterparty, catName, currency())
        }
        if (txnId != null) CaptureOutcome.ADDED else CaptureOutcome.PENDING
    }

    /** Which card a bank-side bill payment went to: the card whose bank is named in the message, or the only card. */
    private suspend fun billCard(body: String): Long? {
        val cards = db.accountDao().getAllWithBalance().map { it.account }.filter { !it.archived && it.type == AccountType.CREDIT_CARD }
        val lower = body.lowercase()
        val named = cards.filter { c -> listOfNotNull(c.bankName, c.name).any { n -> n.length >= 3 && Regex("""\b${Regex.escape(n.lowercase())}\b""").containsMatchIn(lower) } }
        return (named.singleOrNull() ?: cards.singleOrNull())?.id
    }

    /** Returns the new transfer's id when this message is the other half of one already captured. */
    private suspend fun pairAsTransfer(base: CapturedMessageEntity, p: ParsedTxn, accountId: Long?): Long? {
        // Both halves must name an account by its digits; otherwise a coincidence could be mistaken for a transfer.
        if (accountId == null || p.accountLast4 == null) return null
        val t = base.receivedAt
        // A card bill payment shows up on the card hours (sometimes a day) after the bank debit.
        val billWindow = CARD_BILL_WINDOW_MS
        val other = dao.around(t - billWindow, t + billWindow).firstOrNull { c ->
            val window = if (p.kind == CaptureKind.CARD_BILL_PAYMENT || c.kind == CaptureKind.CARD_BILL_PAYMENT.name) billWindow else DUPLICATE_WINDOW_MS
            kotlin.math.abs(c.receivedAt - t) <= window &&
            c.direction != p.direction.name && c.amountMinor == p.amountMinor && c.accountLast4 != null &&
                c.suggestedAccountId != null && c.suggestedAccountId != accountId &&
                (c.transactionId != null || c.kind == CaptureKind.CARD_BILL_PAYMENT.name)
        } ?: return null
        // The bank side was already recorded as a transfer to this card: this message just confirms it.
        other.transactionId?.let { db.transactionDao().getById(it) }?.let { ot ->
            if (ot.type == TxnType.TRANSFER && (ot.toAccountId == accountId || ot.accountId == accountId)) return ot.id
        }
        val otherAccount = other.suggestedAccountId!!
        val (from, to) = if (p.direction == TxnDirection.DEBIT) accountId to otherAccount else otherAccount to accountId
        val id = runCatching {
            db.withTransaction {
                other.transactionId?.let { txns.delete(it) }
                txns.save(
                    TxnInput(
                        type = TxnType.TRANSFER, amountMinor = p.amountMinor, accountId = from, toAccountId = to,
                        dateMillis = minOf(t, other.receivedAt), note = "Auto-captured transfer between your accounts", tags = AUTO_TAG,
                    )
                )
            }
        }.getOrElse { if (it is FinanceException) null else throw it } ?: return null
        dao.update(other.copy(status = CaptureStatus.ADDED, transactionId = id, suggestedCategoryId = null))
        return id
    }

    private suspend fun isDuplicate(msg: RawMessage, p: ParsedTxn, textHash: String): Boolean {
        val t = msg.timestampMillis
        if (p.refNo != null && dao.countRef(p.refNo) > 0) return true
        val near = dao.around(t - EMAIL_DUPLICATE_WINDOW_MS, t + EMAIL_DUPLICATE_WINDOW_MS)
        if (near.any { it.textHash == textHash && kotlin.math.abs(it.receivedAt - t) <= SAME_TEXT_WINDOW_MS }) return true
        return near.any { c ->
            val window = if (PaymentApps.isEmail(c.source) || PaymentApps.isEmail(msg.source)) EMAIL_DUPLICATE_WINDOW_MS else DUPLICATE_WINDOW_MS
            c.direction == p.direction.name && c.amountMinor == p.amountMinor &&
                kotlin.math.abs(c.receivedAt - t) <= window &&
                // Two different sources (SMS, email, UPI app) describing one payment. Two messages from the
                // same source are two real payments (e.g. two ₹20 teas): those are only merged by reference number.
                c.source != msg.source &&
                !(c.refNo != null && p.refNo != null && c.refNo != p.refNo) &&
                // Same amount on two different accounts/cards is two payments.
                !(c.accountLast4 != null && p.accountLast4 != null && !last4Match(c.accountLast4, p.accountLast4))
        }
    }

    private suspend fun resolveAccount(p: ParsedTxn, preferred: Long?, prefs: AutoCapturePreferencesEntity): Long? {
        val active = db.accountDao().getAllWithBalance().map { it.account }.filter { !it.archived }
        p.accountLast4?.let { l4 ->
            val matches = active.filter { a -> a.last4 != null && a.last4.length >= 3 && last4Match(a.last4, l4) }
            val pick = if (p.isCreditCard) matches.firstOrNull { it.type == AccountType.CREDIT_CARD }
            else matches.firstOrNull { it.type == AccountType.BANK } ?: matches.firstOrNull { it.type != AccountType.CREDIT_CARD || p.isCard }
            if (pick != null) return pick.id
        }
        if (p.isCreditCard) {
            // A card spend must never land on a bank account. With one card whose digits don't contradict
            // the message, use it; otherwise ask the user (review list).
            return active.filter { it.type == AccountType.CREDIT_CARD }
                .singleOrNull()?.takeIf { c -> p.accountLast4 == null || c.last4 == null }?.id
        }
        preferred?.let { id -> active.firstOrNull { it.id == id }?.let { return it.id } }
        prefs.defaultAccountId?.let { id -> active.firstOrNull { it.id == id }?.let { return it.id } }
        return active.filter { it.type == AccountType.BANK }.singleOrNull()?.id
    }

    private suspend fun resolveCategory(p: ParsedTxn, body: String): Long? {
        val catType = if (p.direction == TxnDirection.DEBIT) CategoryType.EXPENSE else CategoryType.INCOME
        CategoryGuesser.merchantKey(p.vpa, null)?.let { k -> ruleCategory(k, catType)?.let { return it } }
        CategoryGuesser.merchantKey(null, p.counterparty)?.let { k -> ruleCategory(k, catType)?.let { return it } }
        val guess = CategoryGuesser.guess(listOfNotNull(p.counterparty, p.vpa, body).joinToString(" "), p.direction)
            // Money coming back onto a credit card (that isn't a bill payment) is a refund / reversal / cashback.
            ?: if (p.direction == TxnDirection.CREDIT && p.isCreditCard) "Refund" else null
        val dao = db.categoryDao()
        return guess?.let { dao.findByName(it, catType.name) }?.takeIf { !it.archived }?.id
            ?: dao.findByName("Other", catType.name)?.id
    }

    private suspend fun ruleCategory(key: String, type: CategoryType): Long? {
        val r = dao.rule(key) ?: return null
        val c = db.categoryDao().getById(r.categoryId) ?: return null
        return c.id.takeIf { c.type == type && !c.archived }
    }

    private fun noteFor(source: String, fromPay: Boolean) = when {
        fromPay -> "Paid with Scan & Pay"
        source == SOURCE_SMS -> "Auto-captured from bank SMS"
        else -> "Auto-captured from ${appLabel(source)}"
    }

    private suspend fun currency() = (db.settingsDao().get() ?: SettingsEntity()).currency

    /** Remembers [categoryId] for this merchant so the next payment there is categorised the same way. */
    suspend fun learn(vpa: String?, merchant: String?, categoryId: Long) {
        val now = Dates.nowMillis()
        CategoryGuesser.merchantKey(vpa, null)?.let { dao.upsertRule(MerchantRuleEntity(it, categoryId, now)) }
        CategoryGuesser.merchantKey(null, merchant)?.let { dao.upsertRule(MerchantRuleEntity(it, categoryId, now)) }
    }

    /** Called after the user edits a transaction: if it came from auto-capture, learn the corrected category. */
    suspend fun learnFromEdit(transactionId: Long) {
        val t = db.transactionDao().getById(transactionId) ?: return
        val cat = t.categoryId ?: return
        val captured = dao.forTransaction(transactionId)
        if (captured == null && AUTO_TAG !in t.tags.split(',')) return
        learn(captured?.vpa, t.merchant ?: captured?.counterparty, cat)
    }

    /** Suggested category for a payee before paying (Scan & Pay). */
    suspend fun suggestCategory(vpa: String?, name: String?): Long? {
        CategoryGuesser.merchantKey(vpa, null)?.let { k -> ruleCategory(k, CategoryType.EXPENSE)?.let { return it } }
        CategoryGuesser.merchantKey(null, name)?.let { k -> ruleCategory(k, CategoryType.EXPENSE)?.let { return it } }
        val guess = CategoryGuesser.guess(listOfNotNull(name, vpa).joinToString(" "), TxnDirection.DEBIT) ?: return null
        return db.categoryDao().findByName(guess, CategoryType.EXPENSE.name)?.takeIf { !it.archived }?.id
    }

    // ---- Review list ----

    /** Adds a pending capture to the ledger with the user's choices. Returns the new transaction id. */
    suspend fun approve(captureId: Long, accountId: Long, categoryId: Long, merchant: String?): Long = lock.withLock {
        val c = dao.get(captureId) ?: throw FinanceException("This entry no longer exists")
        if (c.status != CaptureStatus.PENDING) throw FinanceException("This entry was already handled")
        val type = if (c.direction == TxnDirection.DEBIT.name) TxnType.EXPENSE else TxnType.INCOME
        val id = txns.save(
            TxnInput(
                type = type, amountMinor = c.amountMinor, accountId = accountId, categoryId = categoryId, dateMillis = c.receivedAt,
                merchant = merchant?.take(60), note = noteFor(c.source, false), tags = AUTO_TAG,
            )
        )
        dao.update(c.copy(status = CaptureStatus.ADDED, transactionId = id, suggestedAccountId = accountId, suggestedCategoryId = categoryId, counterparty = merchant))
        learn(c.vpa, merchant, categoryId)
        id
    }

    /** Adds every pending entry that has a suggested account and category. Returns how many were added. */
    suspend fun approveAll(): Int {
        val pending = dao.pendingList()
        var n = 0
        pending.filter { it.kind == CaptureKind.NORMAL.name }.forEach { c ->
            val acc = c.suggestedAccountId ?: return@forEach
            val cat = c.suggestedCategoryId ?: return@forEach
            if (runCatching { approve(c.id, acc, cat, c.counterparty) }.isSuccess) n++
        }
        return n
    }

    suspend fun ignore(captureId: Long) = lock.withLock {
        dao.get(captureId)?.let { dao.update(it.copy(status = CaptureStatus.IGNORED)) }
        Unit
    }

    // ---- Scan & Pay ----

    suspend fun startPayment(vpa: String, payeeName: String?, amountMinor: Long, categoryId: Long?, accountId: Long?, note: String?, txnRef: String, appPackage: String?): Long {
        val now = Dates.nowMillis()
        return dao.insertPayment(
            UpiPaymentEntity(
                vpa = vpa, payeeName = payeeName, amountMinor = amountMinor, categoryId = categoryId, accountId = accountId, note = note,
                txnRef = txnRef, appPackage = appPackage, status = UpiPaymentStatus.STARTED, transactionId = null, createdAt = now, updatedAt = now,
            )
        )
    }

    suspend fun payment(id: Long) = dao.payment(id)

    /**
     * The UPI app reported success (or the user confirmed it). Records the expense now, and logs it as a
     * capture so the bank SMS that follows is recognised as the same payment. Returns the transaction id.
     */
    suspend fun completePayment(paymentId: Long, approvalRef: String?): Long = lock.withLock {
        val p = dao.payment(paymentId) ?: throw FinanceException("Payment not found")
        p.transactionId?.let { return it }
        val accountId = p.accountId ?: throw FinanceException("Choose the account you paid from")
        val categoryId = p.categoryId ?: throw FinanceException("Choose a category")
        val now = Dates.nowMillis()
        val id = txns.save(
            TxnInput(
                type = TxnType.EXPENSE, amountMinor = p.amountMinor, accountId = accountId, categoryId = categoryId, dateMillis = now,
                merchant = (p.payeeName ?: p.vpa).take(60), note = p.note?.takeIf { it.isNotBlank() } ?: "Paid with Scan & Pay", tags = AUTO_TAG,
            )
        )
        dao.updatePayment(p.copy(status = UpiPaymentStatus.SUCCESS, transactionId = id, updatedAt = now))
        dao.insert(
            CapturedMessageEntity(
                source = SOURCE_FINFOX_PAY, sender = p.appPackage, body = "Paid ${p.amountMinor} to ${p.vpa}", textHash = hash("finfox-pay-${p.id}"),
                receivedAt = now, direction = TxnDirection.DEBIT.name, amountMinor = p.amountMinor, accountLast4 = null, isCard = false,
                counterparty = p.payeeName, vpa = p.vpa, refNo = approvalRef?.filter { it.isDigit() }?.takeIf { it.length in 9..16 },
                kind = CaptureKind.NORMAL.name, status = CaptureStatus.ADDED, suggestedAccountId = accountId, suggestedCategoryId = categoryId,
                transactionId = id, createdAt = now,
            )
        )
        learn(p.vpa, p.payeeName, categoryId)
        id
    }

    suspend fun failPayment(paymentId: Long) {
        dao.payment(paymentId)?.takeIf { it.status == UpiPaymentStatus.STARTED }?.let {
            dao.updatePayment(it.copy(status = UpiPaymentStatus.FAILED, updatedAt = Dates.nowMillis()))
        }
    }
}

/** Friendly names for the apps FinFox listens to. */
fun appLabel(pkg: String): String = PaymentApps.known[pkg] ?: PaymentApps.emailApps[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
