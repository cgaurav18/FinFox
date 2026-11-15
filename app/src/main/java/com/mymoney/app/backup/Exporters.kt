package com.mymoney.app.backup

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.core.Money
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.domain.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CsvExporter {
    private fun esc(s: String?): String {
        val v = s ?: ""
        // Neutralise spreadsheet formula injection and quote when needed.
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun typeLabel(t: TxnType) = when (t) {
        TxnType.INCOME -> "Income"; TxnType.EXPENSE -> "Expense"; TxnType.TRANSFER -> "Transfer"
        TxnType.LOAN_GIVEN -> "Lent"; TxnType.LOAN_TAKEN -> "Borrowed"
        TxnType.LOAN_REPAYMENT_RECEIVED -> "Repayment received"; TxnType.LOAN_REPAYMENT_PAID -> "Repayment paid"
    }

    fun transactions(list: List<TransactionDetail>, currency: String): String = buildString {
        append("Date,Time,Type,Amount ($currency),Account,To Account,Category,Merchant/Person,Note,Tags\r\n")
        list.sortedBy { it.txn.dateMillis }.forEach { d ->
            val t = d.txn
            append(listOf(
                Dates.toLocalDate(t.dateMillis).toString(), Dates.formatTime(t.dateMillis), typeLabel(t.type),
                Money.format(t.amountMinor, currency, showSymbol = false, alwaysDecimals = true).replace(",", ""),
                d.accountName, d.toAccountName, d.categoryName, t.merchant ?: d.personName, t.note, t.tags.replace(",", " "),
            ).joinToString(",") { esc(it) })
            append("\r\n")
        }
    }
}

@javax.inject.Singleton
class Exporter @javax.inject.Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {
    suspend fun writeCsv(uri: Uri, list: List<TransactionDetail>, currency: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM so Excel shows ₹ correctly
            it.write(CsvExporter.transactions(list, currency).toByteArray())
        } ?: throw BackupException("Couldn't open the file")
    }

    /** A4 report: summary, category breakdown and the transaction list, paginated. */
    suspend fun writePdf(uri: Uri, list: List<TransactionDetail>, range: DateRange, currency: String, owner: String) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 40f
        val title = Paint().apply { textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF5B21B6.toInt(); isAntiAlias = true }
        val head = Paint().apply { textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF111827.toInt(); isAntiAlias = true }
        val body = Paint().apply { textSize = 9.5f; color = 0xFF374151.toInt(); isAntiAlias = true }
        val muted = Paint().apply { textSize = 9f; color = 0xFF6B7280.toInt(); isAntiAlias = true }
        val line = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 1f }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
            page!!.canvas.drawText("My Money • Page $pageNo", margin, h - 20f, muted)
        }
        fun ensure(space: Float) { if (y + space > h - margin) newPage() }
        fun clip(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s
        newPage()
        val c = { page!!.canvas }
        c().drawText("My Money – Financial Report", margin, y + 16, title); y += 30
        c().drawText("${Dates.formatDay(range.start)} – ${Dates.formatDay(range.endInclusive)}" + if (owner.isNotBlank()) "  •  $owner" else "", margin, y + 10, muted); y += 26
        val s = Reports.summary(list)
        listOf("Income" to s.incomeMinor, "Expenses" to s.expenseMinor, "Net savings" to s.netMinor, "Transfers" to s.transferMinor).forEachIndexed { i, (k, v) ->
            val x = margin + i * 130f
            c().drawText(k, x, y + 10, muted)
            c().drawText(Money.format(v, currency), x, y + 26, head)
        }
        y += 44
        val cats = Reports.categoryBreakdown(list)
        if (cats.isNotEmpty()) {
            c().drawText("Spending by category", margin, y + 12, head); y += 22
            cats.forEach { sl ->
                ensure(16f)
                c().drawText(clip(sl.label, 30), margin, y + 10, body)
                c().drawText("${sl.percent}%", margin + 260, y + 10, body)
                c().drawText(Money.format(sl.valueMinor, currency), margin + 330, y + 10, body)
                y += 16
            }
            y += 10
        }
        ensure(40f)
        c().drawText("Transactions (${list.size})", margin, y + 12, head); y += 22
        val cols = floatArrayOf(0f, 62f, 140f, 250f, 360f)
        fun header() {
            listOf("Date", "Type", "Category", "Account", "Amount").forEachIndexed { i, t -> c().drawText(t, margin + cols[i], y + 10, muted) }
            y += 14; c().drawLine(margin, y, w - margin, y, line); y += 4
        }
        header()
        list.sortedBy { it.txn.dateMillis }.forEach { d ->
            if (y + 16 > h - margin) { newPage(); header() }
            val t = d.txn
            c().drawText(Dates.formatDayShort(Dates.toLocalDate(t.dateMillis)), margin + cols[0], y + 10, body)
            c().drawText(clip(CsvExporter.typeLabel(t.type), 16), margin + cols[1], y + 10, body)
            c().drawText(clip(d.categoryName ?: d.personName ?: (d.toAccountName?.let { "→ $it" } ?: "-"), 20), margin + cols[2], y + 10, body)
            c().drawText(clip(d.accountName, 20), margin + cols[3], y + 10, body)
            c().drawText(Money.format(t.amountMinor, currency), margin + cols[4], y + 10, body)
            y += 15
        }
        page?.let { doc.finishPage(it) }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { doc.writeTo(it) } ?: throw BackupException("Couldn't open the file")
        } finally { doc.close() }
    }
}
