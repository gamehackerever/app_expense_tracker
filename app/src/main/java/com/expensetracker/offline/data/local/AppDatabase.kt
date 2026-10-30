package com.expensetracker.offline.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import com.expensetracker.offline.data.local.dao.ReviewItemDao
import com.expensetracker.offline.data.local.dao.SplitDebtDao
import com.expensetracker.offline.data.local.dao.TransactionDao
import com.expensetracker.offline.data.local.entity.CategoryRuleEntity
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        ReviewItemEntity::class,
        CategoryRuleEntity::class,
        SplitDebtEntity::class
    ],
    version = AppDatabase.DATABASE_VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun splitDebtDao(): SplitDebtDao

    companion object {
        const val DATABASE_VERSION = 17

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN itemsSummary TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN receiptImageUri TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN settledAt INTEGER")
                db.execSQL("UPDATE transactions SET settledAt = timestamp WHERE splitNote LIKE 'Settled%'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN bankName TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountNumber TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN linkedDebitId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedDebitId` ON `transactions` (`linkedDebitId`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    UPDATE transactions 
                    SET linkedDebitId = CAST(SUBSTR(splitNote, INSTR(splitNote, '[splitId:') + 9, INSTR(SUBSTR(splitNote, INSTR(splitNote, '[splitId:') + 9), ']') - 1) AS INTEGER) 
                    WHERE splitNote LIKE '%[splitId:%]' AND linkedDebitId IS NULL
                """)
                db.execSQL("UPDATE transactions SET splitNote = NULL WHERE splitNote LIKE 'Settled • Repaid for%'")
                db.execSQL("UPDATE transactions SET splitNote = NULLIF(TRIM(REPLACE(splitNote, 'Settled • ', '')), '') WHERE splitNote LIKE 'Settled • %'")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN isNecessity INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET isNecessity = 1 WHERE category = 'Bills & Utilities'")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE transactions SET isNecessity = 0 WHERE category = 'Bills & Utilities'")
                db.execSQL("UPDATE transactions SET isNecessity = 1 WHERE category = 'Subscription'")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_amount_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_referenceId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_linkedDebitId`")
                db.execSQL("ALTER TABLE `transactions` RENAME TO `transactions_old`")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amount` REAL NOT NULL,
                        `payee` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `referenceId` TEXT,
                        `rawContent` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `note` TEXT,
                        `balance` REAL,
                        `itemsSummary` TEXT,
                        `receiptImageUri` TEXT,
                        `bankName` TEXT,
                        `accountNumber` TEXT,
                        `isNecessity` INTEGER NOT NULL DEFAULT 0,
                        `linkedDebtId` INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO `transactions` (`id`, `amount`, `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, `balance`, `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, `linkedDebtId`)
                    SELECT `id`, `amount`, `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, `balance`, `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, `linkedDebitId`
                    FROM `transactions_old`
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `split_debts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `debtorName` TEXT NOT NULL,
                        `amountOwed` REAL NOT NULL,
                        `settledAt` INTEGER,
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_split_debts_transactionId` ON `split_debts` (`transactionId`)")

                db.execSQL("""
                    INSERT INTO `split_debts` (`transactionId`, `debtorName`, `amountOwed`, `settledAt`)
                    SELECT `id`, COALESCE(NULLIF(TRIM(`splitNote`), ''), 'Unspecified Person'), (`amount` - `myShare`), `settledAt`
                    FROM `transactions_old`
                    WHERE `myShare` IS NOT NULL AND `myShare` < `amount`
                """)

                db.execSQL("""
                    UPDATE `transactions`
                    SET `linkedDebtId` = (
                        SELECT sd.`id` FROM `split_debts` sd
                        WHERE sd.`transactionId` = `transactions`.`linkedDebtId`
                        LIMIT 1
                    )
                    WHERE `linkedDebtId` IS NOT NULL
                """)

                db.execSQL("DROP TABLE `transactions_old`")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_amount_timestamp` ON `transactions` (`amount`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedDebtId` ON `transactions` (`linkedDebtId`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_bankName_accountNumber` ON `transactions` (`bankName`, `accountNumber`)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_split_debts_transactionId`")
                db.execSQL("DROP INDEX IF EXISTS `index_split_debts_transactionId_debtorName`")
                db.execSQL("ALTER TABLE `split_debts` RENAME TO `split_debts_old`")

                db.execSQL("DROP INDEX IF EXISTS `index_transactions_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_amount_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_referenceId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_linkedDebtId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_bankName_accountNumber`")
                db.execSQL("ALTER TABLE `transactions` RENAME TO `transactions_old`")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `payee` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `referenceId` TEXT,
                        `rawContent` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `note` TEXT,
                        `balance` INTEGER,
                        `itemsSummary` TEXT,
                        `receiptImageUri` TEXT,
                        `bankName` TEXT,
                        `accountNumber` TEXT,
                        `isNecessity` INTEGER NOT NULL,
                        `excludeFromSpend` INTEGER NOT NULL,
                        `linkedDebtId` INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO `transactions` (`id`, `amount`, `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, `balance`, `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, `excludeFromSpend`, `linkedDebtId`)
                    SELECT `id`, CAST(ROUND(`amount` * 100) AS INTEGER), `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, CAST(ROUND(`balance` * 100) AS INTEGER), `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, 0, `linkedDebtId`
                    FROM `transactions_old`
                """)

                db.execSQL("""
                    UPDATE `transactions`
                    SET `linkedDebtId` = (
                        SELECT MIN(b.`id`) FROM `split_debts_old` a
                        JOIN `split_debts_old` b ON a.`transactionId` = b.`transactionId` AND a.`debtorName` = b.`debtorName`
                        WHERE a.`id` = `transactions`.`linkedDebtId`
                    )
                    WHERE `linkedDebtId` IN (SELECT `id` FROM `split_debts_old`)
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `split_debts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `debtorName` TEXT NOT NULL,
                        `amountOwed` INTEGER NOT NULL,
                        `originalAmount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `settledAt` INTEGER,
                        `status` TEXT NOT NULL,
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """)

                // Preserve parent transaction timestamp for createdAt instead of hardcoding 0
                db.execSQL("""
                    INSERT INTO `split_debts` (`id`, `transactionId`, `debtorName`, `amountOwed`, `originalAmount`, `createdAt`, `settledAt`, `status`)
                    SELECT MIN(s.`id`), s.`transactionId`, s.`debtorName`,
                           CAST(ROUND(SUM(s.`amountOwed`) * 100) AS INTEGER),
                           CAST(ROUND(SUM(s.`amountOwed`) * 100) AS INTEGER),
                           COALESCE((SELECT t.`timestamp` FROM `transactions` t WHERE t.`id` = s.`transactionId`), (strftime('%s','now') * 1000)),
                           CASE WHEN SUM(CASE WHEN s.`settledAt` IS NULL THEN 1 ELSE 0 END) = 0 THEN MAX(s.`settledAt`) ELSE NULL END,
                           CASE WHEN SUM(CASE WHEN s.`settledAt` IS NULL THEN 1 ELSE 0 END) = 0 THEN 'SETTLED' ELSE 'OPEN' END
                    FROM `split_debts_old` s
                    GROUP BY s.`transactionId`, s.`debtorName`
                """)

                db.execSQL("DROP TABLE `split_debts_old`")
                db.execSQL("DROP TABLE `transactions_old`")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_amount_timestamp` ON `transactions` (`amount`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedDebtId` ON `transactions` (`linkedDebtId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_bankName_accountNumber` ON `transactions` (`bankName`, `accountNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_split_debts_transactionId` ON `split_debts` (`transactionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_split_debts_transactionId_debtorName` ON `split_debts` (`transactionId`, `debtorName`)")

                db.execSQL("""
                    UPDATE `split_debts`
                    SET `status` = 'SETTLED', `settledAt` = (strftime('%s','now') * 1000)
                    WHERE `status` = 'OPEN'
                      AND `amountOwed` <= COALESCE((SELECT SUM(t.`amount`) FROM `transactions` t WHERE t.`linkedDebtId` = `split_debts`.`id`), 0)
                """)

                db.execSQL("ALTER TABLE `review_items` RENAME TO `review_items_old`")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `review_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `rawContent` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `extractedPartialAmount` INTEGER,
                        `extractedPartialPayee` TEXT,
                        `type` TEXT,
                        `bankName` TEXT,
                        `accountNumber` TEXT,
                        `balance` INTEGER,
                        `referenceId` TEXT,
                        `status` TEXT NOT NULL
                    )
                """)

                db.execSQL("""
                    INSERT INTO `review_items` (`id`, `rawContent`, `sender`, `timestamp`, `source`, `extractedPartialAmount`, `extractedPartialPayee`, `status`, `type`, `bankName`, `accountNumber`, `balance`, `referenceId`)
                    SELECT `id`, `rawContent`, `sender`, `timestamp`, `source`, CAST(ROUND(`extractedPartialAmount` * 100) AS INTEGER), `extractedPartialPayee`, `status`, NULL, NULL, NULL, NULL, NULL
                    FROM `review_items_old`
                """)
                db.execSQL("DROP TABLE `review_items_old`")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `paidBy` TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `accountType` TEXT NOT NULL DEFAULT 'BANK'")
                // Retroactively flag known CCs based on your existing logic
                db.execSQL("UPDATE `transactions` SET `accountType` = 'CREDIT_CARD' WHERE bankName LIKE '% CC' OR UPPER(bankName) IN ('ONECARD', 'SLICE', 'AMERICAN EXPRESS', 'LAZYPAY', 'SIMPL')")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `budgetId` TEXT")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "expense_tracker.db"
                ).addMigrations(
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17
                ).fallbackToDestructiveMigrationFrom(1, 2, 3)
                    .build().also { INSTANCE = it }
            }
        }

        /**
         * Safely closes and clears the singleton database instance during backup restore so
         * subsequent accesses cleanly reopen the newly restored database file.
         */
        fun closeAndResetInstance() {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
            }
        }
    }
}
