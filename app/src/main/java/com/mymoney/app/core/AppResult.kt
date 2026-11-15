package com.mymoney.app.core

/** Thrown by repositories when a financial operation violates a business rule. Message is user facing. */
class FinanceException(message: String) : Exception(message)

sealed interface UiMessage {
    data class Info(val text: String) : UiMessage
    data class Error(val text: String) : UiMessage
}

inline fun <T> runCatchingFinance(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: FinanceException) {
    Result.failure(e)
} catch (e: android.database.sqlite.SQLiteConstraintException) {
    Result.failure(FinanceException("This record is linked to other data and can't be changed that way."))
}
