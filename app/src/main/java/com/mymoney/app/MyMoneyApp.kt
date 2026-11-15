package com.mymoney.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.DefaultData
import com.mymoney.app.notifications.AndroidNotifier
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
        appScope.launch { DefaultData.ensureSeeded(db) }
        ReminderWorker.schedule(this)
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
