package com.mymoney.app.domain

import com.google.common.truth.Truth.assertThat
import com.mymoney.app.backup.CsvExporter
import com.mymoney.app.core.DateRange
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.TransactionDetail
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.TxnType
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReportsTest {
    private fun d(type: TxnType, amount: Long, date: LocalDate, cat: Long? = 1, catName: String? = "Food", acc: Long = 1, merchant: String? = null) = TransactionDetail(
        TransactionEntity(id = 0, type = type, amountMinor = amount, accountId = acc, categoryId = cat, dateMillis = Dates.toMillis(date, LocalTime.NOON), merchant = merchant, createdAt = 0, updatedAt = 0),
        accountName = "HDFC", accountType = AccountType.BANK, toAccountName = null, toAccountType = null,
        categoryName = catName, categoryIconKey = "food", categoryColor = 0xFFF97316, personName = null,
    )
    private val day = LocalDate.of(2026, 11, 15)

    @Test fun summaryExcludesTransfersAndLoans() {
        val list = listOf(
            d(TxnType.INCOME, 1000, day), d(TxnType.EXPENSE, 300, day), d(TxnType.TRANSFER, 500, day, null, null),
            d(TxnType.LOAN_GIVEN, 700, day, null, null), d(TxnType.LOAN_REPAYMENT_RECEIVED, 200, day, null, null),
        )
        val s = Reports.summary(list)
        assertThat(s.incomeMinor).isEqualTo(1000); assertThat(s.expenseMinor).isEqualTo(300)
        assertThat(s.netMinor).isEqualTo(700); assertThat(s.transferMinor).isEqualTo(500)
    }

    @Test fun categoryBreakdownSortedWithPercents() {
        val list = listOf(d(TxnType.EXPENSE, 300, day), d(TxnType.EXPENSE, 700, day, 2, "Rent"), d(TxnType.INCOME, 5000, day))
        val slices = Reports.categoryBreakdown(list)
        assertThat(slices.map { it.label }).containsExactly("Rent", "Food").inOrder()
        assertThat(slices.map { it.percent }).containsExactly(70, 30).inOrder()
    }

    @Test fun dailyAndMonthlySeries() {
        val r = DateRange(day.minusDays(2), day)
        val series = Reports.series(listOf(d(TxnType.EXPENSE, 100, day), d(TxnType.INCOME, 50, day.minusDays(2))), r)
        assertThat(series).hasSize(3)
        assertThat(series.first().incomeMinor).isEqualTo(50)
        assertThat(series.last().expenseMinor).isEqualTo(100)
        val long = Reports.series(emptyList(), DateRange(day.minusMonths(5).withDayOfMonth(1), day))
        assertThat(long).hasSize(6)
    }

    @Test fun budgetThresholds() {
        assertThat(Reports.budgetThreshold(79, 100)).isNull()
        assertThat(Reports.budgetThreshold(80, 100)).isEqualTo(80)
        assertThat(Reports.budgetThreshold(95, 100)).isEqualTo(90)
        assertThat(Reports.budgetThreshold(150, 100)).isEqualTo(100)
    }

    @Test fun csvEscapesAndBlocksFormulaInjection() {
        val csv = CsvExporter.transactions(listOf(d(TxnType.EXPENSE, 123_450, day, merchant = "=HYPERLINK(\"x\")"), d(TxnType.EXPENSE, 100, day, merchant = "Cafe, \"Best\"")), "INR")
        val lines = csv.trim().split("\r\n")
        assertThat(lines[0]).startsWith("Date,Time,Type,Amount (INR)")
        assertThat(lines[1]).contains("1234.50")
        assertThat(lines[1]).contains("\"'=HYPERLINK(\"\"x\"\")\"")
        assertThat(lines[2]).contains("\"Cafe, \"\"Best\"\"\"")
    }
}
