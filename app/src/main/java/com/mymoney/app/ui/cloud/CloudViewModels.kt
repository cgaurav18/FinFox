package com.mymoney.app.ui.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mymoney.app.backup.BackupCrypto
import com.mymoney.app.backup.BackupException
import com.mymoney.app.backup.BackupKeyStore
import com.mymoney.app.backup.BackupManager
import com.mymoney.app.backup.BackupPayload
import com.mymoney.app.backup.RestoreMode
import com.mymoney.app.backup.RestoreReport
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.cloud.CloudBackupException
import com.mymoney.app.cloud.CloudBackupService
import com.mymoney.app.cloud.DriveAuthException
import com.mymoney.app.cloud.DriveAuthResult
import com.mymoney.app.cloud.DriveAuthorizer
import com.mymoney.app.cloud.RemoteBackup
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.BackupFrequency
import com.mymoney.app.data.db.BackupMetadataEntity
import com.mymoney.app.data.db.BackupPreferencesEntity
import com.mymoney.app.data.db.UserProfileEntity
import com.mymoney.app.data.repo.ProfileRepository
import com.mymoney.app.notifications.BackupEvent
import com.mymoney.app.notifications.Notifier
import com.mymoney.app.notifications.ReminderWorker
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.ui.components.BaseViewModel
import com.mymoney.app.ui.components.MessageBus
import com.mymoney.app.widgets.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CloudState(
    val loaded: Boolean = false,
    val profile: UserProfileEntity = UserProfileEntity(),
    val prefs: BackupPreferencesEntity = BackupPreferencesEntity(),
    val hasPassphrase: Boolean = false,
    val history: List<BackupMetadataEntity> = emptyList(),
    val backupRunning: Boolean = false,
    val safetyCopyAt: Long? = null,
) {
    val lastSuccess get() = history.firstOrNull { it.status == com.mymoney.app.data.db.BackupStatus.SUCCESS }
    val ready get() = profile.signedIn && profile.driveConnected && !profile.driveNeedsReauth && hasPassphrase
}

/** Google Drive connection, recovery passphrase, automatic backup settings and "Backup Now". */
@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val cloud: CloudBackupService,
    private val profiles: ProfileRepository,
    private val authorizer: DriveAuthorizer,
    private val keys: BackupKeyStore,
    private val backup: BackupManager,
    private val lock: AppLockManager,
    @ApplicationContext private val context: Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val safety = MutableStateFlow(backup.safetyCopyTime())
    private val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_MANUAL)

    val state: StateFlow<CloudState> = combine(
        profiles.profile, cloud.prefs, keys.hasPassphrase, cloud.history(),
        combine(workInfos, safety) { w, s -> w to s },
    ) { p, prefs, has, hist, (w, s) ->
        CloudState(true, p, prefs, has, hist, w.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudState())

    /** Google's consent screen that the UI must launch. */
    private val _consent = Channel<PendingIntent>(Channel.BUFFERED)
    val consentRequests = _consent.receiveAsFlow()

    private var awaitingBackup = false

    init {
        // Report the outcome of a "Backup Now" started from this screen.
        viewModelScope.launch {
            workInfos.collect { list ->
                val w = list.firstOrNull() ?: return@collect
                if (!awaitingBackup || !w.state.isFinished) return@collect
                awaitingBackup = false
                if (w.state == WorkInfo.State.SUCCEEDED) bus.show("Backup uploaded and verified")
                else bus.show(w.outputData.getString(BackupWorker.KEY_ERROR) ?: "Backup didn't complete")
            }
        }
    }

    fun beforeExternalActivity() { lock.suppressNextBackgroundLock = true }

    /** Requests the drive.appdata grant — separately from sign-in, only when the user asks for it. */
    fun connectDrive() = launchSafe {
        val p = profiles.get()
        if (!p.signedIn) throw CloudBackupException.NotSignedIn()
        try {
            when (val r = authorizer.authorize(p.email)) {
                is DriveAuthResult.Granted -> { profiles.setDriveConnected(true); bus.show("Google Drive connected") }
                is DriveAuthResult.NeedsUser -> { beforeExternalActivity(); _consent.send(r.pendingIntent) }
            }
        } catch (e: DriveAuthException) { bus.show(e.message ?: "Couldn't connect Google Drive") }
    }

    fun onConsentResult(resultCode: Int, data: Intent?) = launchSafe {
        if (resultCode != Activity.RESULT_OK) { bus.show("Google Drive wasn't connected"); return@launchSafe }
        try {
            authorizer.tokenFromConsentResult(data) // token is used only to confirm the grant; it isn't stored
            profiles.setDriveConnected(true)
            bus.show("Google Drive connected")
        } catch (e: DriveAuthException) { bus.show(e.message ?: "Google Drive permission was not granted") }
    }

    fun disconnectDrive() = launchSafe("Google Drive disconnected. Existing backups stay in your Drive.") {
        cloud.updatePrefs { it.copy(autoBackupEnabled = false) }
        BackupWorker.syncSchedule(context, cloud.getPrefs())
        try { authorizer.revoke(profiles.get().email) } catch (_: Exception) { /* offline: local disconnect still applies */ }
        profiles.setDriveConnected(false)
    }

    /** Returns an error message, or null on success. */
    suspend fun setPassphrase(p: String, confirm: String, acknowledged: Boolean): String? {
        if (p.length < BackupCrypto.MIN_PASSPHRASE) return "Use at least ${BackupCrypto.MIN_PASSPHRASE} characters"
        if (p != confirm) return "Passphrases don't match"
        if (passphraseStrength(p) < 2) return "Choose a stronger passphrase (mix words, numbers and symbols)"
        if (!acknowledged) return "Please confirm you understand the passphrase can't be recovered"
        return try { keys.setPassphrase(p.toCharArray()); bus.show("Recovery passphrase saved"); null } catch (e: BackupException) { e.message }
    }

    fun backupNow() = launchSafe {
        val s = state.value
        when {
            !s.profile.signedIn -> throw CloudBackupException.NotSignedIn()
            !s.profile.driveConnected || s.profile.driveNeedsReauth -> { connectDrive(); return@launchSafe }
            !s.hasPassphrase -> throw CloudBackupException.NoPassphrase()
        }
        awaitingBackup = true
        BackupWorker.runNow(context)
        bus.show("Backing up… you can leave this screen")
    }

    fun setAuto(on: Boolean) = updatePrefs { it.copy(autoBackupEnabled = on, frequency = if (on && it.frequency == BackupFrequency.MANUAL) BackupFrequency.WEEKLY else it.frequency) }
    fun setFrequency(f: BackupFrequency) = updatePrefs { it.copy(frequency = f, autoBackupEnabled = f != BackupFrequency.MANUAL) }
    fun setWifiOnly(on: Boolean) = updatePrefs { it.copy(wifiOnly = on) }
    fun setKeep(n: Int) = updatePrefs { it.copy(keepCount = n) }

    private fun updatePrefs(t: (BackupPreferencesEntity) -> BackupPreferencesEntity) = launchSafe {
        cloud.updatePrefs(t)
        BackupWorker.syncSchedule(context, cloud.getPrefs())
    }

    fun undoLastRestore() = launchSafe("Your previous data is back") {
        backup.undoLastRestore()
        safety.value = backup.safetyCopyTime()
        ReminderWorker.schedule(context)
        WidgetUpdater.updateAll(context)
    }

    companion object {
        /** 0 (weak) … 4 (strong). Length and character variety. */
        fun passphraseStrength(p: String): Int {
            var score = 0
            if (p.length >= 10) score++
            if (p.length >= 16) score++
            val classes = listOf(p.any(Char::isLowerCase), p.any(Char::isUpperCase), p.any(Char::isDigit), p.any { !it.isLetterOrDigit() }).count { it }
            if (classes >= 2) score++
            if (classes >= 3 || p.count { it == ' ' } >= 2) score++
            if (p.toSet().size <= 3) score = 0
            return score.coerceAtMost(4)
        }
    }
}

/** Lists backups stored in Drive alongside the local attempt log. */
@HiltViewModel
class BackupHistoryViewModel @Inject constructor(private val cloud: CloudBackupService, bus: MessageBus) : BaseViewModel(bus) {
    private val _remote = MutableStateFlow<List<RemoteBackup>?>(null)
    val remote: StateFlow<List<RemoteBackup>?> = _remote.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    val local = cloud.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _error.value = null
        try { _remote.value = cloud.listBackups() } catch (e: CloudBackupException) {
            _error.value = e.message; _remote.value = emptyList()
        } catch (e: Exception) { _error.value = "Couldn't load backups: ${e.message}"; _remote.value = emptyList() }
    }

    fun delete(b: RemoteBackup) = launchSafe("Backup deleted from Google Drive") {
        try { cloud.deleteBackup(b) } catch (e: CloudBackupException) { throw BackupException(e.message ?: "Couldn't delete") }
        _remote.update { it?.filterNot { r -> r.fileId == b.fileId } }
    }
}

enum class RestoreStep { LOADING, PASSPHRASE, DECRYPTING, PREVIEW, RESTORING, DONE, ERROR }

data class RestoreState(
    val step: RestoreStep = RestoreStep.LOADING,
    val source: String = "",
    val remote: RemoteBackup? = null,
    val payload: BackupPayload? = null,
    val currentAccounts: Int = 0,
    val currentTransactions: Int = 0,
    val error: String? = null,
    val report: RestoreReport? = null,
)

/**
 * Restore flow: download → passphrase → verify & decrypt → validate → preview → explicit confirmation
 * (replace or merge) → transactional restore → refresh reminders/widgets → results.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val cloud: CloudBackupService,
    private val backup: BackupManager,
    private val db: AppDatabase,
    private val notifier: Notifier,
    @ApplicationContext private val context: Context,
    bus: MessageBus,
) : BaseViewModel(bus) {
    private val fileId: String? = handle.get<String>("fileId")?.takeIf { it.isNotBlank() }
    private val uri: Uri? = handle.get<String>("uri")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    private var bytes: ByteArray? = null

    private val _s = MutableStateFlow(RestoreState(source = if (fileId != null) "Google Drive" else "Backup file"))
    val state: StateFlow<RestoreState> = _s.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _s.update { it.copy(step = RestoreStep.LOADING, error = null) }
        try {
            if (fileId != null) {
                val b = cloud.listBackups().firstOrNull { it.fileId == fileId } ?: throw BackupException("That backup no longer exists in Google Drive")
                _s.update { it.copy(remote = b) }
                bytes = cloud.download(b)
            } else if (uri != null) {
                bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Couldn't open the file")
                }
                if (bytes!!.size > BackupManager.MAX_BACKUP_BYTES) throw BackupException("File is too large")
            } else throw BackupException("No backup selected")
            BackupCrypto.readHeader(bytes!!) // fail fast on non-backup / corrupted files
            _s.update { it.copy(step = RestoreStep.PASSPHRASE) }
        } catch (e: Exception) {
            _s.update { it.copy(step = RestoreStep.ERROR, error = e.message ?: "Couldn't load the backup") }
        }
    }

    fun decrypt(passphrase: String) = viewModelScope.launch {
        val data = bytes ?: return@launch
        _s.update { it.copy(step = RestoreStep.DECRYPTING, error = null) }
        try {
            val p = if (fileId != null) cloud.decrypt(data, passphrase.toCharArray(), rememberKey = true)
            else withContext(Dispatchers.Default) { backup.decode(BackupCrypto.decrypt(data, passphrase.toCharArray())) }
            val accounts = db.backupDao().accounts().size
            val txns = db.transactionDao().count()
            _s.update { it.copy(step = RestoreStep.PREVIEW, payload = p, currentAccounts = accounts, currentTransactions = txns) }
        } catch (e: BackupException) {
            _s.update { it.copy(step = RestoreStep.PASSPHRASE, error = e.message) }
        } catch (e: Exception) {
            _s.update { it.copy(step = RestoreStep.PASSPHRASE, error = "Couldn't decrypt: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun restore(mode: RestoreMode) = viewModelScope.launch {
        val p = _s.value.payload ?: return@launch
        _s.update { it.copy(step = RestoreStep.RESTORING, error = null) }
        try {
            val report = backup.restore(p, mode)
            ReminderWorker.schedule(context)
            WidgetUpdater.updateAll(context)
            notifier.backupEvent(BackupEvent.RESTORED,
                if (mode == RestoreMode.REPLACE) "All data replaced with the backup from ${com.mymoney.app.core.Dates.formatDay(p.exportedAt)}."
                else "${report.totalInserted} records added, ${report.totalSkipped} duplicates skipped.")
            _s.update { it.copy(step = RestoreStep.DONE, report = report) }
        } catch (e: Exception) {
            _s.update { it.copy(step = RestoreStep.PREVIEW, error = (e.message ?: "Restore failed") + ". Your existing data was not changed.") }
        }
    }
}
