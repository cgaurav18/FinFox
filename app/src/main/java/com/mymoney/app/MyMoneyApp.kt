package com.mymoney.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.DefaultData
import com.mymoney.app.auth.GoogleAuthGateway
import com.mymoney.app.cloud.BackupWorker
import com.mymoney.app.data.repo.ProfileRepository
import com.mymoney.app.notifications.NotificationDispatcher
import com.mymoney.app.widgets.WidgetUpdater
import com.mymoney.app.notifications.ReminderWorker
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.PinManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class MyMoneyApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var lock: AppLockManager
    @Inject lateinit var pins: PinManager

    @Inject lateinit var google: GoogleAuthGateway
    @Inject lateinit var profiles: ProfileRepository

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * If Firebase no longer has a session (signed out elsewhere / account removed) the local profile is
     * marked signed out. Offline, Firebase keeps its cached session, so offline use is never blocked.
     */
    private suspend fun reconcileGoogleSession() {
        val p = profiles.get()
        if (!p.signedIn || !google.isConfigured()) return
        val user = google.currentUser()
        if (user == null) profiles.onSignedOut()
        else if (user.uid != p.googleUid) profiles.onSignedIn(user)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationDispatcher.createChannels(this)
        appScope.launch {
            DefaultData.ensureSeeded(db)
            // Keep the automatic-backup schedule in step with saved preferences (e.g. after an app update).
            db.cloudBackupDao().getPrefs()?.let { BackupWorker.syncSchedule(this@MyMoneyApp, it) }
            reconcileGoogleSession()
        }
        ReminderWorker.schedule(this)
        WidgetUpdater.observe(this, db, appScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = lock.onBackground()
            override fun onStart(owner: LifecycleOwner) {
                // Synchronous so the lock screen is in place before the first frame is drawn.
                val s = runBlocking { pins.current() }
                lock.onForeground(s.autoLockSeconds, s.pinSet)
            }
        })
    }
}
