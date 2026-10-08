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
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.SplitDebtEntity

@Database(
    entities = [
        TransactionEntity::class,
        ReviewItemEntity::class,
        CategoryRuleEntity::class,
        SplitDebtEntity::class
    ],
    version = AppDatabase.DATABASE_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun splitDebtDao(): SplitDebtDao

    companion object {
        const val DATABASE_VERSION = 15
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

        // Adds the linkedDebitId column AND its index
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN linkedDebitId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedDebitId` ON `transactions` (`linkedDebitId`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Recover old [splitId:XXX] text links into the native linkedDebitId column
                db.execSQL("""
                    UPDATE transactions 
                    SET linkedDebitId = CAST(SUBSTR(splitNote, INSTR(splitNote, '[splitId:') + 9, INSTR(SUBSTR(splitNote, INSTR(splitNote, '[splitId:') + 9), ']') - 1) AS INTEGER) 
                    WHERE splitNote LIKE '%[splitId:%]' AND linkedDebitId IS NULL
                """)

                // 2. Erase the auto-generated "Repaid for..." paragraphs from Credit transactions
                db.execSQL("UPDATE transactions SET splitNote = NULL WHERE splitNote LIKE 'Settled • Repaid for%'")

                // 3. For Debits, strip the "Settled • " prefix but KEEP any custom notes
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
                // ORDER MATTERS. split_debts has ON DELETE CASCADE -> transactions. If split_debts
                // exists when the old `transactions` table is DROPped, SQLite runs an implicit
                // DELETE FROM transactions first (foreign keys on), which cascades and wipes every
                // migrated debt. So: move the old table aside, build the new transactions table,
                // THEN create/fill split_debts, and drop the old table last (nothing references it).

                // 1. Move the old table aside (free up its index names first)
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_amount_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_referenceId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_linkedDebitId`")
                db.execSQL("ALTER TABLE `transactions` RENAME TO `transactions_old`")

                // 2. New transactions table (legacy myShare / splitNote / settledAt columns gone)
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

                // 3. Copy rows. linkedDebtId temporarily holds the OLD parent-transaction id;
                //    it is remapped to split_debts.id in step 6.
                db.execSQL("""
                    INSERT INTO `transactions` (`id`, `amount`, `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, `balance`, `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, `linkedDebtId`)
                    SELECT `id`, `amount`, `payee`, `timestamp`, `type`, `source`, `referenceId`, `rawContent`, `category`, `note`, `balance`, `itemsSummary`, `receiptImageUri`, `bankName`, `accountNumber`, `isNecessity`, `linkedDebitId`
                    FROM `transactions_old`
                """)

                // 4. Now it is safe to create split_debts (FK points at the NEW transactions table)
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

                // 5. Old single-person splits -> split_debts rows
                db.execSQL("""
                    INSERT INTO `split_debts` (`transactionId`, `debtorName`, `amountOwed`, `settledAt`)
                    SELECT `id`, COALESCE(NULLIF(TRIM(`splitNote`), ''), 'Unspecified Person'), (`amount` - `myShare`), `settledAt`
                    FROM `transactions_old`
                    WHERE `myShare` IS NOT NULL AND `myShare` < `amount`
                """)

                // 6. Repayment credits: old parent-transaction id -> new split_debts.id
                db.execSQL("""
                    UPDATE `transactions`
                    SET `linkedDebtId` = (
                        SELECT sd.`id` FROM `split_debts` sd
                        WHERE sd.`transactionId` = `transactions`.`linkedDebtId`
                        LIMIT 1
                    )
                    WHERE `linkedDebtId` IS NOT NULL
                """)

                // 7. Old table can go now
                db.execSQL("DROP TABLE `transactions_old`")

                // 8. Indices expected by TransactionEntity
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
                // ORDER MATTERS (same hazard as MIGRATION_11_12). split_debts has ON DELETE CASCADE to
                // transactions, and SQLite rewrites that FK to follow a renamed parent. If we DROP the old
                // transactions table while split_debts still holds rows and foreign keys are on, SQLite
                // runs an implicit DELETE that cascades and wipes every debt. So: move the CHILD aside
                // first, rebuild the parent, rebuild the child, drop the child's old copy, and only then
                // drop the old parent.

                // 0. Move split_debts aside (frees its index names too)
                db.execSQL("DROP INDEX IF EXISTS `index_split_debts_transactionId`")
                db.execSQL("DROP INDEX IF EXISTS `index_split_debts_transactionId_debtorName`")
                db.execSQL("ALTER TABLE `split_debts` RENAME TO `split_debts_old`")

                // 1. TRANSACTIONS TABLE (REAL rupees -> INTEGER paise)
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

                // 2. Older versions allowed two debts for the same person on one bill, which would make
                //    the new UNIQUE (transactionId, debtorName) index fail and crash the upgrade.
                //    Point repayments of the duplicate rows at the surviving (lowest-id) row, then fold them.
                db.execSQL("""
                    UPDATE `transactions`
                    SET `linkedDebtId` = (
                        SELECT MIN(b.`id`) FROM `split_debts_old` a
                        JOIN `split_debts_old` b ON a.`transactionId` = b.`transactionId` AND a.`debtorName` = b.`debtorName`
                        WHERE a.`id` = `transactions`.`linkedDebtId`
                    )
                    WHERE `linkedDebtId` IN (SELECT `id` FROM `split_debts_old`)
                """)

                // 3. SPLIT DEBTS TABLE
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

                // amountOwed == originalAmount == the person's share (repayments never change it).
                db.execSQL("""
                    INSERT INTO `split_debts` (`id`, `transactionId`, `debtorName`, `amountOwed`, `originalAmount`, `createdAt`, `settledAt`, `status`)
                    SELECT MIN(`id`), `transactionId`, `debtorName`,
                           CAST(ROUND(SUM(`amountOwed`) * 100) AS INTEGER),
                           CAST(ROUND(SUM(`amountOwed`) * 100) AS INTEGER),
                           0,
                           CASE WHEN SUM(CASE WHEN `settledAt` IS NULL THEN 1 ELSE 0 END) = 0 THEN MAX(`settledAt`) ELSE NULL END,
                           CASE WHEN SUM(CASE WHEN `settledAt` IS NULL THEN 1 ELSE 0 END) = 0 THEN 'SETTLED' ELSE 'OPEN' END
                    FROM `split_debts_old`
                    GROUP BY `transactionId`, `debtorName`
                """)

                // 4. Child's old copy first, THEN the old parent (nothing references it any more)
                db.execSQL("DROP TABLE `split_debts_old`")
                db.execSQL("DROP TABLE `transactions_old`")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_amount_timestamp` ON `transactions` (`amount`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_referenceId` ON `transactions` (`referenceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedDebtId` ON `transactions` (`linkedDebtId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_bankName_accountNumber` ON `transactions` (`bankName`, `accountNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_split_debts_transactionId` ON `split_debts` (`transactionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_split_debts_transactionId_debtorName` ON `split_debts` (`transactionId`, `debtorName`)")

                // Debts already fully covered by linked repayments become SETTLED
                db.execSQL("""
                    UPDATE `split_debts`
                    SET `status` = 'SETTLED', `settledAt` = (strftime('%s','now') * 1000)
                    WHERE `status` = 'OPEN'
                      AND `amountOwed` <= COALESCE((SELECT SUM(t.`amount`) FROM `transactions` t WHERE t.`linkedDebtId` = `split_debts`.`id`), 0)
                """)

                // 5. REVIEW ITEMS TABLE (no foreign keys involved)
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
                    MIGRATION_14_15
                ).build().also { INSTANCE = it }
            }
        }
    }
}
