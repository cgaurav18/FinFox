package com.mymoney.app.data.db

import androidx.room.withTransaction

object DefaultData {
    private fun c(name: String, type: CategoryType, icon: String, color: Long, order: Int) =
        CategoryEntity(name = name, type = type, iconKey = icon, colorArgb = color, isSystem = true, sortOrder = order)

    val expenseCategories = listOf(
        c("Food", CategoryType.EXPENSE, "food", 0xFFF97316, 1),
        c("Shopping", CategoryType.EXPENSE, "shopping", 0xFFEC4899, 2),
        c("Groceries", CategoryType.EXPENSE, "groceries", 0xFF22C55E, 3),
        c("Fuel", CategoryType.EXPENSE, "fuel", 0xFFEF4444, 4),
        c("Transport", CategoryType.EXPENSE, "transport", 0xFF3B82F6, 5),
        c("Rent", CategoryType.EXPENSE, "rent", 0xFF8B5CF6, 6),
        c("EMI", CategoryType.EXPENSE, "emi", 0xFF6366F1, 7),
        c("Bills", CategoryType.EXPENSE, "bills", 0xFFF59E0B, 8),
        c("Medical", CategoryType.EXPENSE, "medical", 0xFF14B8A6, 9),
        c("Entertainment", CategoryType.EXPENSE, "entertainment", 0xFFA855F7, 10),
        c("Education", CategoryType.EXPENSE, "education", 0xFF0EA5E9, 11),
        c("Travel", CategoryType.EXPENSE, "travel", 0xFF06B6D4, 12),
        c("Other", CategoryType.EXPENSE, "other", 0xFF64748B, 99),
    )

    val incomeCategories = listOf(
        c("Salary", CategoryType.INCOME, "salary", 0xFF16A34A, 1),
        c("Freelancing", CategoryType.INCOME, "freelance", 0xFF0891B2, 2),
        c("Business", CategoryType.INCOME, "business", 0xFF7C3AED, 3),
        c("Interest", CategoryType.INCOME, "interest", 0xFFCA8A04, 4),
        c("Refund", CategoryType.INCOME, "refund", 0xFF2563EB, 5),
        c("Gift", CategoryType.INCOME, "gift", 0xFFDB2777, 6),
        c("Other", CategoryType.INCOME, "other", 0xFF64748B, 99),
    )

    /** Idempotent: inserts default categories and the settings row when missing. */
    suspend fun ensureSeeded(db: AppDatabase) {
        db.withTransaction {
            if (db.categoryDao().count() == 0) db.categoryDao().insertAll(expenseCategories + incomeCategories)
            if (db.settingsDao().get() == null) db.settingsDao().upsert(SettingsEntity())
        }
    }
}
