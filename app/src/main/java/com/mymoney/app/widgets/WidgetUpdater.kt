package com.mymoney.app.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.updateAll
import androidx.room.InvalidationTracker
import com.mymoney.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Catalogue of the home screen widgets, used by the updater and the in-app Widget Gallery. */
enum class WidgetKind(
    val title: String,
    val description: String,
    val sizesLabel: String,
    val receiver: Class<*>,
    val previewSize: DpSize,
) {
    TOTAL_BALANCE("Total Balance", "Available balance (bank + cash) with a hide/show toggle. Opens the dashboard.", "Small · Medium", TotalBalanceReceiver::class.java, WidgetSizes.MEDIUM),
    INCOME_EXPENSE("Income & Expenses", "This month's income, expenses, net savings and top spending. Opens Reports.", "Medium · Large", IncomeExpenseReceiver::class.java, WidgetSizes.LARGE),
    SCAN_PAY("Scan & Pay", "One tap opens the QR scanner to pay any UPI QR. The wide size adds Pay UPI ID and Receive.", "Small · Medium", ScanPayReceiver::class.java, WidgetSizes.MEDIUM),
    RECEIVE_QR("My UPI QR", "Your UPI ID as a QR code on the home screen, so anyone can scan and pay you. Hidden in privacy mode.", "Small · Large", ReceiveQrReceiver::class.java, WidgetSizes.LARGE),
    QUICK_ACTIONS("Quick Actions", "Add an expense, income or transfer, or Scan & Pay, in one tap (wide size adds lending).", "Small · Medium", QuickActionsReceiver::class.java, WidgetSizes.MEDIUM),
    UPCOMING("Upcoming Payments", "EMIs, SIPs, bills and subscriptions with due dates and overdue flags.", "Medium · Large", UpcomingPaymentsReceiver::class.java, WidgetSizes.LARGE),
    PEOPLE("Money with People", "What you'll receive and owe, with upcoming due dates.", "Medium · Large", MoneyWithPeopleReceiver::class.java, WidgetSizes.LARGE),
    BUDGET("Budget Tracker", "Monthly budget, spent, remaining and category progress with overspending warnings.", "Medium · Large", BudgetReceiver::class.java, WidgetSizes.LARGE),
    CREDIT_CARDS("Credit Card Summary", "Card outstanding, available credit and the next payment due.", "Medium · Large", CreditCardReceiver::class.java, WidgetSizes.LARGE);

    fun widget(): GlanceAppWidget = when (this) {
        TOTAL_BALANCE -> TotalBalanceWidget(); INCOME_EXPENSE -> IncomeExpenseWidget(); QUICK_ACTIONS -> QuickActionsWidget()
        UPCOMING -> UpcomingPaymentsWidget(); PEOPLE -> MoneyWithPeopleWidget(); BUDGET -> BudgetWidget(); CREDIT_CARDS -> CreditCardWidget()
        SCAN_PAY -> ScanPayWidget(); RECEIVE_QR -> ReceiveQrWidget()
    }

    /** Loads live data and returns the widget's content (shared by real widgets and previews). */
    suspend fun content(repo: WidgetRepository): @Composable () -> Unit = when (this) {
        TOTAL_BALANCE -> repo.balance().let { m -> { TotalBalanceContent(m) } }
        INCOME_EXPENSE -> repo.month().let { m -> { IncomeExpenseContent(m) } }
        QUICK_ACTIONS -> { { QuickActionsContent() } }
        UPCOMING -> repo.upcoming().let { m -> { UpcomingContent(m) } }
        PEOPLE -> repo.people().let { m -> { PeopleContent(m) } }
        BUDGET -> repo.budget().let { m -> { BudgetContent(m) } }
        CREDIT_CARDS -> repo.cards().let { m -> { CreditCardContent(m) } }
        SCAN_PAY -> { { ScanPayContent() } }
        RECEIVE_QR -> repo.upiQr().let { m -> { ReceiveQrContent(m) } }
    }

    /** Renders the real Glance layout with live data into RemoteViews for an in-app preview. */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    suspend fun renderPreview(context: Context, repo: WidgetRepository, size: DpSize = previewSize): RemoteViews {
        val c = content(repo)
        return GlanceRemoteViews().compose(context, size) {
            WidgetTheme { CompositionLocalProvider(LocalInteractive provides false) { c() } }
        }.remoteViews
    }

    /** Asks the launcher to pin this widget (Android 8+, supported launchers only). */
    fun requestPin(context: Context): Boolean {
        val mgr = context.getSystemService(AppWidgetManager::class.java) ?: return false
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }

    suspend fun placedCount(context: Context): Int = GlanceAppWidgetManager(context).getGlanceIds(widget().javaClass).size
}

/**
 * Refreshes widgets when financial data changes. Room's invalidation tracker tells us which tables
 * changed (no polling, no service); bursts are coalesced so a multi-row transaction refreshes once.
 * Date roll-overs are handled by the periodic reminder work.
 */
object WidgetUpdater {
    private val TABLES = arrayOf(
        "transactions", "accounts", "scheduled_payments", "scheduled_payment_occurrences", "loan_ledger",
        "loan_repayments", "budgets", "categories", "settings", "savings_goals",
    )

    fun observe(
        context: Context, db: AppDatabase, scope: CoroutineScope,
        debounceMs: Long = 1_500, refresh: suspend () -> Unit = { updateAll(context) },
    ) {
        val trigger = Channel<Unit>(Channel.CONFLATED)
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) { trigger.trySend(Unit) }
        })
        scope.launch {
            for (x in trigger) {
                delay(debounceMs)
                trigger.tryReceive() // coalesce anything that arrived while waiting
                try { refresh() } catch (_: Exception) { }
            }
        }
    }

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        WidgetKind.entries.forEach { k -> try { k.widget().updateAll(app) } catch (_: Exception) { } }
    }
}
