package com.mymoney.app.data.repo

import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Google account (email only) holds this device's Drive backups. There is no app sign-in; the
 * account is chosen in Google's own picker when the user connects Google Drive.
 */
@Singleton
class ProfileRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.profileDao()

    val profile: Flow<UserProfileEntity> = dao.observe().map { it ?: UserProfileEntity() }
    suspend fun get(): UserProfileEntity = dao.get() ?: UserProfileEntity()

    /**
     * Records a Drive connection for the Google account the user picked. Choosing a different account
     * than before turns automatic backup off until the user re-enables it for the new account.
     */
    suspend fun onDriveConnected(email: String?) {
        val old = get()
        val switched = old.email != null && email != null && !old.email.equals(email, ignoreCase = true)
        dao.upsert(old.copy(email = email ?: old.email, driveConnected = true, driveConnectedAt = Dates.nowMillis(), driveNeedsReauth = false))
        if (switched) db.cloudBackupDao().getPrefs()?.let { db.cloudBackupDao().upsertPrefs(it.copy(autoBackupEnabled = false)) }
    }

    /** Disconnects Drive locally (financial data and existing Drive backups are untouched). */
    suspend fun onDriveDisconnected() {
        dao.upsert(UserProfileEntity())
        db.cloudBackupDao().getPrefs()?.let { db.cloudBackupDao().upsertPrefs(it.copy(autoBackupEnabled = false)) }
    }

    suspend fun setDriveConnected(connected: Boolean) {
        val p = get()
        dao.upsert(p.copy(driveConnected = connected, driveConnectedAt = if (connected) Dates.nowMillis() else null, driveNeedsReauth = false))
    }

    suspend fun setDriveNeedsReauth(needs: Boolean) = dao.upsert(get().copy(driveNeedsReauth = needs))
}
