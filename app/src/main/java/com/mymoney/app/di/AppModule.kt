package com.mymoney.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.Migrations
import com.mymoney.app.notifications.AndroidNotifier
import com.mymoney.app.notifications.Notifier
import com.mymoney.app.security.AppLockManager
import com.mymoney.app.security.KeystoreSecretBox
import com.mymoney.app.security.PinManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides @Singleton
    fun securityStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("security") }

    @Provides @Singleton
    fun pinManager(store: DataStore<Preferences>): PinManager = PinManager(store, KeystoreSecretBox())

    @Provides @Singleton
    fun appLock(): AppLockManager = AppLockManager()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun notifier(impl: AndroidNotifier): Notifier
}
