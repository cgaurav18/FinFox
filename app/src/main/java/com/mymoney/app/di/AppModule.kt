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
import com.mymoney.app.security.SecretBox
import com.mymoney.app.backup.BackupKeyStore
import com.mymoney.app.cloud.DriveAuthorizer
import com.mymoney.app.cloud.DriveRemote
import com.mymoney.app.cloud.PlayServicesDriveAuthorizer
import com.mymoney.app.cloud.RetrofitDriveRemote
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import com.mymoney.app.data.repo.AppearanceRepository
import com.mymoney.app.data.repo.SettingsRepository

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

    /** UI preferences (theme). Separate file from the security store. */
    @Provides @Singleton @Named("appearance")
    fun appearanceStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("appearance") }

    @Provides @Singleton
    fun appearance(@Named("appearance") store: DataStore<Preferences>, settings: SettingsRepository): AppearanceRepository =
        AppearanceRepository(store, settings)

    @Provides @Singleton
    fun myUpiIds(@Named("appearance") store: DataStore<Preferences>): com.mymoney.app.data.repo.MyUpiRepository =
        com.mymoney.app.data.repo.MyUpiRepository(store)

    @Provides @Singleton
    fun secretBox(): SecretBox = KeystoreSecretBox()

    @Provides @Singleton
    fun pinManager(store: DataStore<Preferences>, box: SecretBox): PinManager = PinManager(store, box)

    @Provides @Singleton
    fun backupKeyStore(store: DataStore<Preferences>, box: SecretBox): BackupKeyStore = BackupKeyStore(store, box)

    @Provides @Singleton
    fun driveRemote(): DriveRemote = RetrofitDriveRemote()

    @Provides @Singleton
    fun appLock(): AppLockManager = AppLockManager()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun notifier(impl: AndroidNotifier): Notifier
    @Binds abstract fun driveAuthorizer(impl: PlayServicesDriveAuthorizer): DriveAuthorizer
}
