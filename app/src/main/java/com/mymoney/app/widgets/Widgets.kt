package com.mymoney.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.ImageProvider
import androidx.glance.layout.size
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.mymoney.app.R
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.LoanDirection
import com.mymoney.app.data.db.TxnType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetRepository(): WidgetRepository
}

fun widgetRepo(context: Context): WidgetRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetRepository()

private val isWide @Composable get() = LocalSize.current.width >= 200.dp
private val isTall @Composable get() = LocalSize.current.height >= 180.dp

// ---------------------------------------------------------------- 1. Total balance (small, medium)

class TotalBalanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.SMALL, WidgetSizes.MEDIUM))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).balance()
        provideContent { WidgetTheme { TotalBalanceContent(m) } }
    }
}

class ToggleBalanceCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetRepo(context).toggleBalanceHidden()
        WidgetUpdater.updateAll(context)
    }
}

@Composable
fun TotalBalanceContent(m: BalanceModel) {
    val f = m.fmt
    WidgetContainer(openAction("home")) {
        WidgetHeader("FinFox", "Updated ${Dates.formatTime(m.updatedAt)}") {
            Box(GlanceModifier.padding(4.dp).clickTo(actionRunCallback<ToggleBalanceCallback>())) {
                Icon(if (m.hidden) R.drawable.ic_w_visibility_off else R.drawable.ic_w_visibility, if (m.hidden) "Show balance" else "Hide balance", if (m.hidden) WidgetColors.highlight else GlanceTheme.colors.onSurfaceVariant)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        if (isWide) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(GlanceModifier.defaultWeight()) {
                    Label("Total available balance", size = 11.sp)
                    Amount(f.money(m.availableMinor), size = 22.sp, color = WidgetColors.brand)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Bank ${f.compact(m.bankMinor)}", size = 11.sp, color = GlanceTheme.colors.onSurface)
                    Label("Cash ${f.compact(m.cashMinor)}", size = 11.sp, color = GlanceTheme.colors.onSurface)
                    Label("Card due ${f.compact(m.cardOutstandingMinor)}", size = 11.sp, color = WidgetColors.expense)
                }
            }
        } else {
            Label("Available", size = 11.sp)
            Amount(f.money(m.availableMinor), size = 18.sp, color = WidgetColors.brand)
            Label("Bank ${f.compact(m.bankMinor)} · Cash ${f.compact(m.cashMinor)}", size = 10.sp)
        }
    }
}

// ---------------------------------------------------------------- 2. Income & expenses (medium, large)

class IncomeExpenseWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.MEDIUM, WidgetSizes.LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).month()
        provideContent { WidgetTheme { IncomeExpenseContent(m) } }
    }
}

@Composable
fun IncomeExpenseContent(m: MonthModel) {
    val f = m.fmt
    WidgetContainer(openAction("reports")) {
        WidgetHeader("Income & Expenses", Dates.formatMonth(m.month)) {
            if (!f.masked && m.incomeMinor > 0) Chip("${(m.spentShare * 100).toInt()}% spent", if (m.spentShare >= 1f) WidgetColors.expense else GlanceTheme.colors.primary)
        }
        VSpace(4)
        Row(GlanceModifier.fillMaxWidth()) {
            StatTile("Income", f.compact(m.incomeMinor), WidgetColors.income, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatTile("Expenses", f.compact(m.expenseMinor), WidgetColors.expense, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatTile("Net savings", f.compact(m.netMinor), if (m.netMinor >= 0) WidgetColors.income else WidgetColors.expense, GlanceModifier.defaultWeight())
        }
        VSpace(6)
        Progress(m.spentShare, if (m.spentShare >= 1f) WidgetColors.expense else GlanceTheme.colors.primary)
        if (isTall) {
            Label(if (f.masked) "Spending vs income" else "${(m.spentShare * 100).toInt()}% of income spent", size = 10.sp)
            VSpace(6)
            Label("Top spending", size = 11.sp, bold = true, color = GlanceTheme.colors.onSurface)
            if (m.categories.isEmpty()) EmptyLine("No expenses yet this month.")
            val top = m.categories.maxOfOrNull { it.amountMinor } ?: 1
            // Nested columns: RemoteViews keep at most 10 children per Row/Column.
            Column(GlanceModifier.fillMaxWidth()) {
                m.categories.forEach { c ->
                    Column(GlanceModifier.fillMaxWidth().padding(top = 3.dp)) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(c.name, size = 11.sp, modifier = GlanceModifier.defaultWeight())
                            Label(f.compact(c.amountMinor), size = 11.sp, bold = true, color = GlanceTheme.colors.onSurface)
                        }
                        Progress(c.amountMinor.toFloat() / top, androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(c.colorArgb)))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 3. Quick actions (small, medium)

class QuickActionsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.SMALL, WidgetSizes.MEDIUM))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { QuickActionsContent() } }
    }
}

private data class QuickAction(val label: String, val icon: Int, val route: String, val tint: androidx.glance.unit.ColorProvider, val description: String)

@Composable
fun QuickActionsContent() {
    val actions = listOf(
        QuickAction("Expense", R.drawable.ic_w_expense, "txn-edit?id=-1&type=${TxnType.EXPENSE.name}", WidgetColors.expense, "Add expense"),
        QuickAction("Income", R.drawable.ic_w_income, "txn-edit?id=-1&type=${TxnType.INCOME.name}", WidgetColors.income, "Add income"),
        QuickAction("Transfer", R.drawable.ic_w_transfer, "txn-edit?id=-1&type=${TxnType.TRANSFER.name}", WidgetColors.transfer, "Transfer money"),
        QuickAction("Pay", R.drawable.ic_shortcut_scan, "pay?scan=true", WidgetColors.brand, "Scan a UPI QR and pay"),
        QuickAction("Lending", R.drawable.ic_w_people, "loan-edit?id=-1&direction=${LoanDirection.GAVE.name}", WidgetColors.highlight, "Add lending or borrowing entry"),
    )
    WidgetContainer(null, padding = 8) {
        if (isWide) {
            WidgetHeader("Quick actions")
            VSpace(6)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    ActionButton(a, GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
        } else {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                ActionButton(actions[0], GlanceModifier.defaultWeight().fillMaxHeight()); Spacer(GlanceModifier.width(6.dp))
                ActionButton(actions[1], GlanceModifier.defaultWeight().fillMaxHeight())
            }
            VSpace(6)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                ActionButton(actions[2], GlanceModifier.defaultWeight().fillMaxHeight()); Spacer(GlanceModifier.width(6.dp))
                ActionButton(actions[3], GlanceModifier.defaultWeight().fillMaxHeight())  // Pay (Scan & Pay)
            }
        }
    }
}

@Composable
private fun ActionButton(a: QuickAction, modifier: GlanceModifier) {
    Column(
        modifier.background(ImageProvider(R.drawable.widget_tile)).cornerRadius(14.dp).padding(4.dp).clickTo(openAction(a.route)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(a.icon, a.description, a.tint, size = 22)
        Label(a.label, size = 10.sp, bold = true, color = GlanceTheme.colors.onSurface)
    }
}

// ---------------------------------------------------------------- 4. Upcoming payments (medium, large)

class UpcomingPaymentsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.MEDIUM, WidgetSizes.LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).upcoming()
        provideContent { WidgetTheme { UpcomingContent(m) } }
    }
}

@Composable
fun UpcomingContent(m: UpcomingModel) {
    WidgetContainer(openAction("schedules")) {
        WidgetHeader("Upcoming payments", if (!isTall) null else if (m.overdueCount > 0) "${m.overdueCount} overdue" else "EMI, SIP, bills & subscriptions") {
            if (m.overdueCount > 0) Chip("Overdue", WidgetColors.overdue)
        }
        VSpace(4)
        if (m.rows.isEmpty()) EmptyLine("No upcoming payments. Add EMIs, SIPs and bills in Scheduled Payments.")
        Column(GlanceModifier.fillMaxWidth()) { m.rows.take(if (isTall) 5 else 2).forEach { r ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickTo(openAction("schedule/${r.id}")), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Label(r.title, size = 12.sp, bold = true, color = GlanceTheme.colors.onSurface)
                    Label("${r.category.label} · ${Dates.formatDayShort(r.due)} · ${r.dueLabel}", size = 10.sp,
                        color = if (r.overdue) WidgetColors.expense else GlanceTheme.colors.onSurfaceVariant)
                }
                Amount(m.fmt.compact(r.amountMinor), size = 13.sp, color = if (r.overdue) WidgetColors.expense else GlanceTheme.colors.onSurface)
            }
        } }
    }
}

// ---------------------------------------------------------------- 5. Money with people (medium, large)

class MoneyWithPeopleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.MEDIUM, WidgetSizes.LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).people()
        provideContent { WidgetTheme { PeopleContent(m) } }
    }
}

@Composable
fun PeopleContent(m: PeopleModel) {
    val f = m.fmt
    WidgetContainer(openAction("people")) {
        WidgetHeader("Money with People", if (isTall) "Net ${f.compact(m.netMinor)}" else null)
        VSpace(4)
        Row(GlanceModifier.fillMaxWidth()) {
            StatTile("To receive", f.compact(m.toReceiveMinor), WidgetColors.income, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatTile("To pay", f.compact(m.toPayMinor), WidgetColors.expense, GlanceModifier.defaultWeight())
        }
        if (isTall) {
            VSpace(6)
            Label("Upcoming due dates", size = 11.sp, bold = true, color = GlanceTheme.colors.onSurface)
            if (m.rows.isEmpty()) EmptyLine("No due dates set.")
            Column(GlanceModifier.fillMaxWidth()) { m.rows.take(4).forEach { r ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickTo(openAction("loan/${r.loanId}")), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Label(if (r.gave) "${r.person} owes you" else "You owe ${r.person}", size = 12.sp, bold = true, color = GlanceTheme.colors.onSurface)
                        Label("${Dates.formatDayShort(r.due)} · ${r.dueLabel}", size = 10.sp, color = if (r.overdue) WidgetColors.expense else GlanceTheme.colors.onSurfaceVariant)
                    }
                    Amount(f.compact(r.outstandingMinor), size = 13.sp, color = if (r.gave) WidgetColors.income else WidgetColors.expense)
                }
            } }
        } else m.rows.firstOrNull()?.let { r ->
            VSpace(4)
            Label("Next: ${r.person} · ${r.dueLabel}", size = 11.sp, color = if (r.overdue) WidgetColors.expense else GlanceTheme.colors.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- 6. Budget tracker (medium, large)

class BudgetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.MEDIUM, WidgetSizes.LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).budget()
        provideContent { WidgetTheme { BudgetContent(m) } }
    }
}

@Composable
fun BudgetContent(m: BudgetModel) {
    val f = m.fmt
    WidgetContainer(openAction("budgets")) {
        WidgetHeader(if (isTall) "Budget" else "Budget · ${Dates.formatMonthShort(m.month)}", if (isTall) Dates.formatMonth(m.month) else null) {
            if (m.overCount > 0) Chip("${m.overCount} over", WidgetColors.expense)
        }
        VSpace(4)
        if (m.rows.isEmpty()) { EmptyLine("No budgets for this month. Set category limits in Budgets."); return@WidgetContainer }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(GlanceModifier.defaultWeight()) {
                Label("Spent of ${f.compact(m.limitMinor)}", size = 10.sp)
                Amount(f.money(m.spentMinor), size = 16.sp, color = if (m.spentMinor > m.limitMinor) WidgetColors.expense else GlanceTheme.colors.onSurface)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label(if (m.remainingMinor >= 0) "Remaining" else "Overspent", size = 10.sp)
                Amount(f.compact(kotlin.math.abs(m.remainingMinor)), size = 13.sp, color = if (m.remainingMinor >= 0) WidgetColors.income else WidgetColors.expense)
            }
        }
        VSpace(4)
        Progress(m.fraction, when { m.fraction >= 1f -> WidgetColors.expense; m.fraction >= 0.8f -> WidgetColors.warning; else -> GlanceTheme.colors.primary })
        if (m.overCount > 0 && !isTall) Label("⚠ Overspending in ${m.overCount} categor${if (m.overCount == 1) "y" else "ies"}", size = 10.sp, color = WidgetColors.expense)
        if (isTall) {
            VSpace(6)
            Column(GlanceModifier.fillMaxWidth()) {
                m.rows.take(4).forEach { r ->
                    Column(GlanceModifier.fillMaxWidth().padding(top = 3.dp)) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(r.name + if (r.over) " ⚠" else "", size = 11.sp, color = if (r.over) WidgetColors.expense else GlanceTheme.colors.onSurface, modifier = GlanceModifier.defaultWeight())
                            Label(if (f.masked) "${r.percent}%" else "${f.compact(r.spentMinor)} / ${f.compact(r.limitMinor)}", size = 10.sp)
                        }
                        Progress(r.fraction, when { r.over || r.percent >= 100 -> WidgetColors.expense; r.percent >= 80 -> WidgetColors.warning; else -> GlanceTheme.colors.primary })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 7. Credit cards (medium, large)

class CreditCardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.MEDIUM, WidgetSizes.LARGE))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).cards()
        provideContent { WidgetTheme { CreditCardContent(m) } }
    }
}

@Composable
fun CreditCardContent(m: CardModel) {
    val f = m.fmt
    WidgetContainer(openAction("accounts")) {
        WidgetHeader("Credit cards", if (isTall) "Credit limits aren't counted as cash" else null)
        VSpace(4)
        if (m.cards.isEmpty()) { EmptyLine("No credit cards added yet."); return@WidgetContainer }
        Row(GlanceModifier.fillMaxWidth()) {
            StatTile("Outstanding", f.compact(m.outstandingMinor), WidgetColors.expense, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            StatTile("Available credit", f.compact(m.availableCreditMinor), GlanceTheme.colors.onSurface, GlanceModifier.defaultWeight())
        }
        VSpace(4)
        val next = m.nextDue
        if (next != null && !isTall) {
            Label("Next due: ${next.name} · ${Dates.formatDayShort(next.nextDue!!)} · ${f.compact(next.outstandingMinor)}", size = 11.sp,
                color = if (next.nextDue.isBefore(m.today.plusDays(4))) WidgetColors.expense else GlanceTheme.colors.onSurface)
        } else if (next != null) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Label("Next payment due", size = 11.sp)
                    Label("${next.name} · ${Dates.formatDayShort(next.nextDue!!)} (${Dates.dueLabel(next.nextDue, m.today)})", size = 11.sp, bold = true, color = GlanceTheme.colors.onSurface)
                }
                Amount(f.compact(next.outstandingMinor), size = 14.sp, color = WidgetColors.expense)
            }
        } else Label("No card payment due", size = 11.sp)
        if (isTall) {
            VSpace(6)
            Column(GlanceModifier.fillMaxWidth()) {
                m.cards.take(3).forEach { c ->
                    Column(GlanceModifier.fillMaxWidth().padding(top = 3.dp).clickTo(openAction("account-detail/${c.id}"))) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(c.name, size = 11.sp, color = GlanceTheme.colors.onSurface, modifier = GlanceModifier.defaultWeight())
                            Label(f.compact(c.outstandingMinor) + (c.limitMinor?.let { " / " + f.compact(it) } ?: ""), size = 10.sp)
                        }
                        c.limitMinor?.takeIf { it > 0 }?.let { lim ->
                            val u = c.outstandingMinor.toFloat() / lim
                            Progress(u, if (u >= 0.8f) WidgetColors.expense else GlanceTheme.colors.primary)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Receivers

// ---------------------------------------------------------------- 8. Scan & Pay (one fixed layout)

/**
 * A single "Scan & Pay" button at every size. [SizeMode.Single] on purpose: with responsive sizes launchers
 * switch between their portrait/landscape measurements and the widget would flip between layouts on its own.
 */
class ScanPayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { ScanPayContent() } }
    }
}

@Composable
fun ScanPayContent() {
    WidgetContainer(null, padding = 6) {
        BigAction("Scan & Pay", R.drawable.ic_shortcut_scan, "pay?scan=true", "Scan a UPI QR and pay", GlanceModifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------- 10. Pay & Receive bar (one fixed layout)

class PayReceiveWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetTheme { PayReceiveContent() } }
    }
}

/** Scan & Pay · Pay UPI ID · Receive, always side by side. */
@Composable
fun PayReceiveContent() {
    WidgetContainer(null, padding = 6) {
        Row(GlanceModifier.fillMaxSize()) {
            BigAction("Scan & Pay", R.drawable.ic_shortcut_scan, "pay?scan=true", "Scan a UPI QR and pay", GlanceModifier.defaultWeight().fillMaxHeight())
            Spacer(GlanceModifier.width(6.dp))
            BigAction("Pay UPI ID", R.drawable.ic_w_transfer, "pay?scan=false", "Pay a UPI ID", GlanceModifier.defaultWeight().fillMaxHeight())
            Spacer(GlanceModifier.width(6.dp))
            BigAction("Receive", R.drawable.ic_shortcut_receive, "receive", "Show my UPI QR", GlanceModifier.defaultWeight().fillMaxHeight())
        }
    }
}

@Composable
private fun BigAction(label: String, icon: Int, route: String, description: String, modifier: GlanceModifier) {
    Column(
        modifier.background(ImageProvider(R.drawable.widget_tile)).cornerRadius(16.dp).padding(6.dp).clickTo(openAction(route)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, description, WidgetColors.brand, size = 30)
        VSpace(3)
        Label(label, size = 11.sp, bold = true, color = GlanceTheme.colors.onSurface)
    }
}

// ---------------------------------------------------------------- 9. My UPI QR (medium, large)

class ReceiveQrWidget : GlanceAppWidget() {
    /** One layout (the QR scales to the widget), so it never switches on its own. */
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = widgetRepo(context).upiQr()
        provideContent { WidgetTheme { ReceiveQrContent(m) } }
    }
}

/** The default UPI ID as a scannable QR (black on white). Hidden in widget privacy mode; tap opens Receive money. */
@Composable
fun ReceiveQrContent(m: UpiQrModel?) {
    WidgetContainer(openAction("receive"), padding = 10) {
        when {
            m == null -> {
                WidgetHeader("My UPI QR")
                VSpace(6)
                EmptyLine("Tap to save your UPI ID. Its QR shows here so anyone can scan and pay you.")
            }
            m.hidden -> {
                WidgetHeader("My UPI QR", m.vpa)
                VSpace(6)
                EmptyLine("Hidden by widget privacy mode. Tap to show it in FinFox.")
            }
            else -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Label(m.name, size = 13.sp, bold = true, color = GlanceTheme.colors.onSurface)
                VSpace(4)
                androidx.glance.layout.Box(
                    GlanceModifier.defaultWeight().background(androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color.White)).cornerRadius(10.dp).padding(5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.glance.Image(ImageProvider(qrWidgetBitmap(m.payload)), contentDescription = "UPI QR code for ${m.vpa}",
                        modifier = GlanceModifier.fillMaxHeight(), contentScale = androidx.glance.layout.ContentScale.Fit)
                }
                VSpace(4)
                Label(m.vpa, size = 10.sp)
            }
        }
    }
}

/** QR bitmap for widgets (kept small: widgets travel through a binder with a size limit). */
private fun qrWidgetBitmap(payload: String): android.graphics.Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, com.google.zxing.EncodeHintType.MARGIN to 1)
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 360, 360, hints)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
}

class TotalBalanceReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TotalBalanceWidget() }
class IncomeExpenseReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = IncomeExpenseWidget() }
class QuickActionsReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget() }
class UpcomingPaymentsReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UpcomingPaymentsWidget() }
class MoneyWithPeopleReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MoneyWithPeopleWidget() }
class BudgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = BudgetWidget() }
class CreditCardReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = CreditCardWidget() }
class ScanPayReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ScanPayWidget() }
class ReceiveQrReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ReceiveQrWidget() }
class PayReceiveReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = PayReceiveWidget() }
