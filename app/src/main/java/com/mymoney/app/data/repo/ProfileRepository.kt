package com.mymoney.app.data.repo

import com.mymoney.app.auth.GoogleAccountInfo
import com.mymoney.app.core.Dates
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local record of the linked Google account. Signing out or switching accounts never touches financial
 * data; it only resets the identity and the Drive connection (which belongs to the previous account).
 */
@Singleton
class ProfileRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.profileDao()

    val profile: Flow<UserProfileEntity> = dao.observe().map { it ?: UserProfileEntity() }
    suspend fun get(): UserProfileEntity = dao.get() ?: UserProfileEntity()

    /** Returns true when this is a different Google account than the one previously linked. */
    suspend fun onSignedIn(info: GoogleAccountInfo): Boolean {
        val old = get()
        val switched = old.googleUid != null && old.googleUid != info.uid
        dao.upsert(
            UserProfileEntity(
                googleUid = info.uid, email = info.email, displayName = info.displayName, photoUrl = info.photoUrl,
                signedIn = true, signedInAt = Dates.nowMillis(),
                // A Drive grant belongs to one Google account; a different account must connect again.
                driveConnected = if (switched) false else old.driveConnected,
                driveConnectedAt = if (switched) null else old.driveConnectedAt,
                driveNeedsReauth = if (switched) false else old.driveNeedsReauth,
            )
        )
        if (switched) {
            // Auto backup would otherwise target the new account without the user asking for it.
            db.cloudBackupDao().getPrefs()?.let { db.cloudBackupDao().upsertPrefs(it.copy(autoBackupEnabled = false)) }
        }
        return switched
    }

    suspend fun onSignedOut() {
        dao.upsert(UserProfileEntity())
        db.cloudBackupDao().getPrefs()?.let { db.cloudBackupDao().upsertPrefs(it.copy(autoBackupEnabled = false)) }
    }

    suspend fun setDriveConnected(connected: Boolean) {
        val p = get()
        dao.upsert(p.copy(driveConnected = connected, driveConnectedAt = if (connected) Dates.nowMillis() else null, driveNeedsReauth = false))
    }

    suspend fun setDriveNeedsReauth(needs: Boolean) = dao.upsert(get().copy(driveNeedsReauth = needs))
}
