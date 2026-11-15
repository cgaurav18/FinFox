package com.mymoney.app.ui.account

import android.app.Activity
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.mymoney.app.auth.GoogleAccountInfo
import com.mymoney.app.auth.GoogleAuthException
import com.mymoney.app.auth.GoogleAuthGateway
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.cloud.CloudBackupService
import com.mymoney.app.cloud.DriveAuthorizer
import com.mymoney.app.data.db.BackupPreferencesEntity
import com.mymoney.app.data.db.UserProfileEntity
import com.mymoney.app.data.repo.ProfileRepository
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountState(
    val profile: UserProfileEntity = UserProfileEntity(),
    val backup: BackupPreferencesEntity = BackupPreferencesEntity(),
    val configured: Boolean = true,
)

/** Google account linking: sign-in, account switching, sign-out and access revocation. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val google: GoogleAuthGateway,
    private val profiles: ProfileRepository,
    private val drive: DriveAuthorizer,
    cloud: CloudBackupService,
    private val lock: AppLockManager,
    @ApplicationContext private val context: Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    val state: StateFlow<AccountState> = combine(profiles.profile, cloud.prefs) { p, b -> AccountState(p, b, google.isConfigured()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountState(configured = google.isConfigured()))

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    /** Opens Google's account chooser. [onSignedIn] runs after Firebase verified the account. */
    fun signIn(activity: Activity, onSignedIn: (GoogleAccountInfo) -> Unit = {}) {
        if (_signingIn.value) return
        viewModelScope.launch {
            _signingIn.value = true
            // The account chooser is a separate window; don't treat it as the app going to the background.
            lock.suppressNextBackgroundLock = true
            try {
                val info = google.signIn(activity)
                val switched = profiles.onSignedIn(info)
                bus.show(if (switched) "Switched to ${info.email}. Connect Google Drive again for this account." else "Signed in as ${info.email}")
                onSignedIn(info)
            } catch (e: GoogleAuthException) {
                if (!e.cancelled) bus.show(e.message ?: "Sign-in failed")
            } catch (e: Exception) {
                bus.show("Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _signingIn.value = false
            }
        }
    }

    /** Signs out of Google. Local financial data is never deleted. */
    fun signOut() = launchSafe("Signed out. Your data on this device is unchanged.") {
        google.signOut()
        profiles.onSignedOut()
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.UNIQUE_AUTO)
    }

    /** Signs out and immediately shows the account chooser. */
    fun switchAccount(activity: Activity) = viewModelScope.launch {
        google.signOut()
        signIn(activity)
    }

    /** Revokes the Drive grant, then signs out. Backups already in Drive stay there under the user's control. */
    fun revokeAccess() = launchSafe("Access revoked and signed out. Your data on this device is unchanged.") {
        val email = profiles.get().email
        try { drive.revoke(email) } catch (e: Exception) { bus.show(e.message ?: "Couldn't reach Google to revoke Drive access") }
        profiles.setDriveConnected(false)
        google.signOut()
        profiles.onSignedOut()
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.UNIQUE_AUTO)
    }
}
