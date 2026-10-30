package com.expensetracker.offline.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.expensetracker.offline.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real SQLite upgrade path and verifies that user transaction data survives it. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun upgradeFromVersion15_preservesTransactionsAndAddsNewColumns() {
        helper.createDatabase(TEST_DB, 15).apply {
            execSQL(
                """INSERT INTO transactions
                    (id, amount, payee, timestamp, type, source, referenceId, rawContent, category,
                     note, balance, itemsSummary, receiptImageUri, bankName, accountNumber,
                     isNecessity, excludeFromSpend, linkedDebtId, paidBy)
                    VALUES (41, 2599, 'Grocery Store', 1700000000000, 'DEBIT', 'SMS', 'REF-41',
                     'Bank alert', 'Groceries', 'weekly shopping', 125000, NULL, NULL,
                     'HDFC Bank', '1234', 0, 0, NULL, 'You')"""
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            AppDatabase.DATABASE_VERSION,
            true,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17
        )
        migrated.query(
            "SELECT amount, payee, referenceId, accountType, budgetId FROM transactions WHERE id = 41"
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Transaction was lost during migration" }
            assertEquals(2599L, cursor.getLong(0))
            assertEquals("Grocery Store", cursor.getString(1))
            assertEquals("REF-41", cursor.getString(2))
            assertEquals("BANK", cursor.getString(3))
            assertNull(cursor.getString(4))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
