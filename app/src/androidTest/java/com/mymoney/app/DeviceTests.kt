package com.mymoney.app

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.mymoney.app.data.db.AccountEntity
import com.mymoney.app.data.db.AccountType
import com.mymoney.app.data.db.AppDatabase
import com.mymoney.app.data.db.DefaultData
import com.mymoney.app.data.db.Migrations
import com.mymoney.app.data.db.TransactionEntity
import com.mymoney.app.data.db.TxnType
import com.mymoney.app.security.KeystoreSecretBox
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    @Test fun sealsAndOpensWithHardwareBackedKey() {
        val box = KeystoreSecretBox("test_alias")
        val plain = "salt+hash".toByteArray()
        val sealed = box.seal(plain)
        assertThat(sealed).isNotEqualTo(plain)
        assertThat(box.open(sealed)).isEqualTo(plain)
    }
}

@RunWith(AndroidJUnit4::class)
class DatabaseDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /** Validates that the exported v1 schema matches the entities. Add a migrateAndValidate per new version. */
    @Test fun schemaV1IsValidAndMigrationsChain() {
        helper.createDatabase("migration-test", 1).close()
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "migration-test")
            .addMigrations(*Migrations.ALL).build().apply { openHelper.writableDatabase; close() }
    }

    @Test fun migrate1To2KeepsFinancialData() {
        helper.createDatabase("migration-1-2", 1).apply {
            execSQL("INSERT INTO categories (id, name, type, iconKey, colorArgb, isSystem, archived, sortOrder) VALUES (1, 'Food', 'EXPENSE', 'food', 0, 1, 0, 1)")
            execSQL("INSERT INTO accounts (id, type, name, openingBalanceMinor, currency, colorArgb, iconKey, archived, createdAt, updatedAt) VALUES (1, 'BANK', 'HDFC', 100000, 'INR', 0, 'bank', 0, 0, 0)")
            execSQL("INSERT INTO transactions (id, type, amountMinor, accountId, categoryId, dateMillis, tags, createdAt, updatedAt) VALUES (1, 'EXPENSE', 25000, 1, 1, 0, '', 0, 0)")
            close()
        }
        helper.runMigrationsAndValidate("migration-1-2", 2, true, Migrations.MIGRATION_1_2).close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "migration-1-2").addMigrations(*Migrations.ALL).build()
        runBlocking { assertThat(db.accountDao().getWithBalance(1)!!.balanceMinor).isEqualTo(75_000) }
        db.close()
    }

    @Test fun balancesSurviveReopenOnDevice(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("device-persist.db")
        fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, "device-persist.db").build()
        val db = open()
        DefaultData.ensureSeeded(db)
        val now = System.currentTimeMillis()
        val acc = db.accountDao().insert(AccountEntity(type = AccountType.BANK, name = "HDFC", openingBalanceMinor = 100_000, colorArgb = 0, iconKey = "bank", createdAt = now, updatedAt = now))
        val food = db.categoryDao().findByName("Food", "EXPENSE")!!.id
        db.transactionDao().insert(TransactionEntity(type = TxnType.EXPENSE, amountMinor = 25_000, accountId = acc, categoryId = food, dateMillis = now, createdAt = now, updatedAt = now))
        db.close()
        val again = open()
        assertThat(again.accountDao().getWithBalance(acc)!!.balanceMinor).isEqualTo(75_000)
        again.close()
        ctx.deleteDatabase("device-persist.db")
    }
}
