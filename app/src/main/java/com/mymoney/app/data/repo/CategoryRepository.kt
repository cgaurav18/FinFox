package com.mymoney.app.data.repo

import com.mymoney.app.core.FinanceException
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.CategoryEntity
import com.mymoney.app.data.db.CategoryType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.categoryDao()

    fun observe(type: CategoryType?, includeArchived: Boolean = false) = dao.observe(type?.name, includeArchived)
    suspend fun get(id: Long) = dao.getById(id)

    suspend fun save(id: Long?, name: String, type: CategoryType, iconKey: String, colorArgb: Long): Long {
        val n = name.trim()
        if (n.isEmpty()) throw FinanceException("Enter a category name")
        if (n.length > 30) throw FinanceException("Name is too long")
        val dup = dao.findByName(n, type.name)
        if (dup != null && dup.id != id) throw FinanceException("A ${type.name.lowercase()} category named $n already exists")
        return if (id == null) {
            dao.insert(CategoryEntity(name = n, type = type, iconKey = iconKey, colorArgb = colorArgb, sortOrder = dao.maxSortOrder() + 1))
        } else {
            val old = dao.getById(id) ?: throw FinanceException("Category not found")
            if (old.type != type) throw FinanceException("Category type can't be changed")
            dao.update(old.copy(name = n, iconKey = iconKey, colorArgb = colorArgb)); id
        }
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val c = dao.getById(id) ?: throw FinanceException("Category not found")
        dao.update(c.copy(archived = archived))
    }

    /** Categories used by transactions can only be archived, never deleted. */
    suspend fun delete(id: Long) {
        val c = dao.getById(id) ?: throw FinanceException("Category not found")
        if (c.isSystem) throw FinanceException("Built-in categories can be archived but not deleted")
        val used = db.transactionDao().countForCategory(id)
        if (used > 0) throw FinanceException("${c.name} is used by $used transaction(s). Archive it instead.")
        dao.delete(c)
    }
}
