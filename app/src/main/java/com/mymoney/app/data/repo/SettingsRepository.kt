package com.mymoney.app.data.repo

import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val db: AppDatabase) {
    val settings: Flow<SettingsEntity> = db.settingsDao().observe().map { it ?: SettingsEntity() }
    suspend fun get(): SettingsEntity = db.settingsDao().get() ?: SettingsEntity()
    suspend fun update(transform: (SettingsEntity) -> SettingsEntity) = db.settingsDao().upsert(transform(get()))
}
