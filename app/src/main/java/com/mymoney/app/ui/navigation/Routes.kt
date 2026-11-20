package com.mymoney.app.ui.navigation

object Routes {
    const val HOME = "home"
    const val TXNS = "transactions?type={type}"
    const val REPORTS = "reports"
    const val MORE = "more"

    const val TXN_EDIT = "txn-edit?id={id}&type={type}"
    const val TXN_DETAIL = "txn-detail/{id}"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_EDIT = "account-edit?id={id}&type={type}"
    const val ACCOUNT_DETAIL = "account-detail/{id}"
    const val SCHEDULES = "schedules"
    const val SCHEDULE_EDIT = "schedule-edit?id={id}"
    /** pay=true opens the "Mark as paid" confirmation (used by the notification action). */
    const val SCHEDULE_DETAIL = "schedule/{id}?pay={pay}"
    const val PEOPLE = "people"
    const val LOAN_EDIT = "loan-edit?id={id}&direction={direction}&person={person}"
    const val LOAN_DETAIL = "loan/{id}"
    const val PERSON = "person/{id}"
    const val BUDGETS = "budgets"
    const val GOALS = "goals"
    const val GOAL_EDIT = "goal-edit?id={id}"
    const val GOAL_DETAIL = "goal/{id}"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val CHANGE_PIN = "change-pin"
    const val BACKUP = "backup"
    const val DRIVE = "drive"
    const val BACKUP_HISTORY = "backup-history"
    const val RESTORE = "restore?fileId={fileId}&uri={uri}"
    const val WIDGETS = "widgets"
    const val NOTIFICATIONS = "notifications"
    const val NOTIFICATION_PREFS = "notification-settings"
    /** scan=true opens the QR scanner straight away; false starts with "pay a UPI ID". */
    const val PAY = "pay?scan={scan}"
    const val AUTO_CAPTURE = "auto-capture"

    fun txns(type: String? = null) = "transactions" + (type?.let { "?type=$it" } ?: "")
    fun txnEdit(id: Long? = null, type: String? = null) = "txn-edit?id=${id ?: -1}" + (type?.let { "&type=$it" } ?: "")
    fun txnDetail(id: Long) = "txn-detail/$id"
    fun accountEdit(id: Long? = null, type: String? = null) = "account-edit?id=${id ?: -1}" + (type?.let { "&type=$it" } ?: "")
    fun accountDetail(id: Long) = "account-detail/$id"
    fun scheduleEdit(id: Long? = null) = "schedule-edit?id=${id ?: -1}"
    fun scheduleDetail(id: Long) = "schedule/$id"
    fun loanEdit(id: Long? = null, direction: String? = null, person: String? = null) =
        "loan-edit?id=${id ?: -1}" + (direction?.let { "&direction=$it" } ?: "") + (person?.let { "&person=" + android.net.Uri.encode(it) } ?: "")
    fun loanDetail(id: Long) = "loan/$id"
    fun person(id: Long) = "person/$id"
    fun goalEdit(id: Long? = null) = "goal-edit?id=${id ?: -1}"
    fun goalDetail(id: Long) = "goal/$id"
    fun restoreDrive(fileId: String) = "restore?fileId=" + android.net.Uri.encode(fileId)
    fun pay(scan: Boolean = true) = "pay?scan=$scan"
    fun restoreFile(uri: String) = "restore?uri=" + android.net.Uri.encode(uri)

    val topLevel = setOf(HOME, TXNS, REPORTS, MORE)
}
