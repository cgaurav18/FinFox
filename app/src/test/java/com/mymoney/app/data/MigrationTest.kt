package com.mymoney.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.Migrations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Existing v1 users upgrade to v2 without losing or changing a single financial row.
 *
 * A real v1 database is created from the exported v1 schema (app/schemas/.../1.json), then opened
 * with the current Room database. Room runs MIGRATION_1_2 and validates every table against the v2
 * entities, failing the open if the migration doesn't produce exactly the expected schema.
 * (DeviceTests also runs MigrationTestHelper on a device.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-v1-v2.db"

    private fun schema(version: Int): File =
        listOf(File("schemas"), File("app/schemas")).map { File(it, "com.mymoney.app.data.db.AppDatabase/$version.json") }.first { it.exists() }

    private fun createV1(): File {
        val json = Json.parseToJsonElement(schema(1).readText()).jsonObject["database"]!!.jsonObject
        val file = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            json["entities"]!!.jsonArray.forEach { e ->
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            json["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = 1
            db.execSQL("INSERT INTO settings (id, profileName, currency, themeMode, notificationsEnabled, budgetAlertsEnabled, reminderHour) VALUES (1, 'Asha', 'INR', 'DARK', 1, 0, 8)")
            db.execSQL("INSERT INTO categories (id, name, type, iconKey, colorArgb, isSystem, archived, sortOrder) VALUES (1, 'Food', 'EXPENSE', 'food', 0, 1, 0, 1)")
            db.execSQL("INSERT INTO accounts (id, type, name, openingBalanceMinor, currency, colorArgb, iconKey, archived, createdAt, updatedAt) VALUES (1, 'BANK', 'HDFC', 100000, 'INR', 0, 'bank', 0, 0, 0)")
            db.execSQL("INSERT INTO transactions (id, type, amountMinor, accountId, categoryId, dateMillis, tags, createdAt, updatedAt) VALUES (1, 'EXPENSE', 25000, 1, 1, 0, '', 0, 0)")
            db.execSQL("INSERT INTO notification_reminders (refType, refId, dueKey, notifiedAt) VALUES ('SCHEDULE', 3, 20000, 0)")
        }
        return file
    }

    @Test fun v1DataSurvivesMigrationToV2() {
        createV1()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            assertThat(db.accountDao().getWithBalance(1)!!.balanceMinor).isEqualTo(75_000)
            val s = db.settingsDao().get()!!
            assertThat(s.profileName).isEqualTo("Asha")
            assertThat(s.reminderHour).isEqualTo(8)
            assertThat(s.budgetAlertsEnabled).isFalse()
            assertThat(s.hideBalances).isFalse()
            assertThat(db.backupDao().reminders()).hasSize(1)
            // New tables exist and start empty; defaults come from the entities.
            assertThat(db.profileDao().get()).isNull()
            assertThat(db.notificationDao().getPrefs()).isNull()
            assertThat(db.cloudBackupDao().getPrefs()).isNull()
            assertThat(db.widgetDao().getPrefs()).isNull()
        }
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
        db.close()
        ctx.deleteDatabase(name)
    }

    @Test fun migrationIsRequiredNotDestructive() {
        createV1()
        // Without the migration Room must refuse to open instead of wiping the user's data.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).allowMainThreadQueries().build()
        val e = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        db.close()
        ctx.deleteDatabase(name)
    }
}
