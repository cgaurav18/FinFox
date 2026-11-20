package com.mymoney.app.autocapture

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPI and bank apps whose notifications report completed payments. Chat and shopping apps are left out on
 * purpose: a friend's "I paid ₹500" or a delayed refund notice would be mistaken for a payment.
 */
object PaymentApps {
    val known: Map<String, String> = linkedMapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.mobikwik_new" to "MobiKwik",
        "money.super.payments" to "super.money",
        "com.naviapp" to "Navi",
        "com.freecharge.android" to "Freecharge",
        "com.sbi.lotusintouch" to "YONO SBI",
        "com.sbi.upi" to "BHIM SBI Pay",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.hdfcbank.payzapp" to "PayZapp",
        "com.csam.icici.bank.imobile" to "iMobile ICICI",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak",
        "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge" to "Kotak 811",
        "com.bankofbaroda.mconnect" to "bob World",
        "com.idfcfirstbank.optimus" to "IDFC FIRST",
        "com.fampay.in" to "FamPay",
        "com.jupiter.money" to "Jupiter",
        "com.fi.money" to "Fi",
    )

    /** SMS apps: their notifications carry bank SMS text when FinFox can't read SMS directly. */
    val smsApps = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms",
        "com.android.messaging", "com.miui.mms", "com.coloros.mms", "com.truecaller",
    )

    /** Email apps: bank alert emails ("Rs 499 debited from your Credit Card ending 5678"). */
    val emailApps: Map<String, String> = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
        "com.samsung.android.email.provider" to "Samsung Email",
        "com.zoho.mail" to "Zoho Mail",
    )
    fun isEmail(source: String) = source in emailApps

    /**
     * Email notifications show the subject and a preview of the body. Both are needed: Axis puts "spent" only in
     * the subject ("INR 60 spent on credit card no. XX3392") and the merchant only in the body. Only the start is
     * kept, so footers (offers, "view your statement") don't get in the way.
     */
    fun emailText(subject: String?, preview: String?): String {
        val s = subject?.trim().orEmpty()
        val p = preview?.trim().orEmpty()
        val text = when {
            p.isEmpty() -> s
            s.isEmpty() || p.startsWith(s) -> p
            else -> "$s. $p"
        }
        return text.take(500)
    }

    /** Only emails that come from a bank / card issuer are read; personal mail is never parsed. */
    private val bankEmailRegex = Regex(
        """\b(?:bank|alerts?|credit\s*card|hdfc|icici|sbi|sbi\s*card|axis|kotak|idfc|indusind|yes\s*bank|rbl|federal|canara|pnb|baroda|bob|union\s*bank|au\s*small|amex|american\s*express|citi|hsbc|standard\s*chartered|onecard|scapia|upi)\b""",
        RegexOption.IGNORE_CASE,
    )
    fun looksLikeBankEmail(sender: String?): Boolean = sender != null && bankEmailRegex.containsMatchIn(sender)

    /** Bank SMS sender IDs as shown by SMS apps: "AD-HDFCBK", "JM-SBIUPI-S", "HDFCBK". */
    private val bankSenderRegex = Regex("""^(?:[A-Z]{2}-)?[A-Z0-9]{4,9}(?:-[A-Z])?$""")
    fun looksLikeBankSender(title: String?): Boolean = title != null && bankSenderRegex.matches(title.trim())
}

object CapturePermissions {
    fun hasSms(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun canReadInbox(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

/** Live bank SMS. Runs only while the user has granted RECEIVE_SMS. */
@AndroidEntryPoint
class SmsCaptureReceiver : BroadcastReceiver() {
    @Inject lateinit var capture: CaptureRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in parts; join them per sender.
        val messages = parts.filterNotNull().groupBy { it.originatingAddress }.map { (sender, list) ->
            RawMessage(CaptureRepository.SOURCE_SMS, sender, list.joinToString("") { it.messageBody.orEmpty() }, list.first().timestampMillis)
        }
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val prefs = capture.prefs()
                if (prefs.enabled && prefs.readSms) messages.forEach { runCatching { capture.process(it) } }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}

/** Notifications from UPI / bank apps (and SMS apps, as a fallback when SMS permission isn't granted). */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {
    @Inject lateinit var capture: CaptureRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val isPaymentApp = pkg in PaymentApps.known
        val isSmsApp = pkg in PaymentApps.smsApps
        val isEmailApp = PaymentApps.isEmail(pkg)
        if (!isPaymentApp && !isSmsApp && !isEmailApp) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        val time = sbn.postTime
        scope.launch {
            val prefs = capture.prefs()
            if (!prefs.enabled || !prefs.readNotifications) return@launch
            val msg = if (isEmailApp) {
                // Title is the sender ("HDFC Bank InstaAlerts"), the text is subject + preview. Only the start of
                // the mail is used, so footers ("view your statement", offers) don't get in the way.
                if (!PaymentApps.looksLikeBankEmail(title)) return@launch
                val subject = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                RawMessage(pkg, title, PaymentApps.emailText(subject, big), time)
            } else if (isSmsApp) {
                // The SMS receiver already sees these with the real sender; only use SMS-app notifications without it.
                if (prefs.readSms && CapturePermissions.hasSms(this@PaymentNotificationListener)) return@launch
                if (!PaymentApps.looksLikeBankSender(title)) return@launch
                RawMessage(pkg, title, body, time)
            } else {
                // Payment apps often split the message: "₹250 paid" in the title, "to Swiggy" in the text.
                RawMessage(pkg, PaymentApps.known[pkg], listOfNotNull(title, body).joinToString(". "), time)
            }
            runCatching { capture.process(msg) }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Ask Android to bind again (e.g. after the app was updated).
        runCatching { requestRebind(ComponentName(this, PaymentNotificationListener::class.java)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

data class ImportResult(val scanned: Int, val found: Int)

/** One-time import of recent bank SMS from the inbox. Everything goes to the review list. */
@Singleton
class SmsInboxImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capture: CaptureRepository,
) {
    suspend fun import(days: Int): ImportResult {
        if (!CapturePermissions.canReadInbox(context)) return ImportResult(0, 0)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val msgs = mutableListOf<RawMessage>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1) ?: continue
                msgs += RawMessage(CaptureRepository.SOURCE_SMS, c.getString(0), body, c.getLong(2))
            }
        }
        var found = 0
        msgs.forEach { m ->
            val r = runCatching { capture.process(m, forceReview = true, notify = false) }.getOrNull()
            if (r == CaptureOutcome.PENDING || r == CaptureOutcome.ADDED) found++
        }
        return ImportResult(msgs.size, found)
    }
}
