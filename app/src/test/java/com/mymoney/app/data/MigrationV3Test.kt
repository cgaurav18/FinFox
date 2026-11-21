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

/** v2 users upgrade to v3 (auto-capture tables) with every financial row intact; Room validates the schema on open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV3Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-v2-v3.db"

    private fun schema(version: Int): File =
        listOf(File("schemas"), File("app/schemas")).map { File(it, "com.mymoney.app.data.db.AppDatabase/$version.json") }.first { it.exists() }

    @Test fun v2DataSurvivesMigrationToV3() {
        val json = Json.parseToJsonElement(schema(2).readText()).jsonObject["database"]!!.jsonObject
        val file = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            json["entities"]!!.jsonArray.forEach { e ->
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            json["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = 2
            db.execSQL("INSERT INTO categories (id, name, type, iconKey, colorArgb, isSystem, archived, sortOrder) VALUES (1, 'Food', 'EXPENSE', 'food', 0, 1, 0, 1)")
            db.execSQL("INSERT INTO accounts (id, type, name, openingBalanceMinor, currency, colorArgb, iconKey, archived, createdAt, updatedAt) VALUES (1, 'BANK', 'HDFC', 100000, 'INR', 0, 'bank', 0, 0, 0)")
            db.execSQL("INSERT INTO transactions (id, type, amountMinor, accountId, categoryId, dateMillis, tags, createdAt, updatedAt) VALUES (1, 'EXPENSE', 25000, 1, 1, 0, '', 0, 0)")
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            assertThat(db.accountDao().getWithBalance(1)!!.balanceMinor).isEqualTo(75_000)
            assertThat(db.captureDao().getPrefs()).isNull()
            assertThat(db.captureDao().pendingList()).isEmpty()
        }
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(AppDatabase.VERSION)
        db.close()
        ctx.deleteDatabase(name)
    }
}
