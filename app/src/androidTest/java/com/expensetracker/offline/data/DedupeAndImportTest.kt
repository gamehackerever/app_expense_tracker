package com.expensetracker.offline.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.expensetracker.offline.data.local.AppDatabase
import com.expensetracker.offline.data.local.entity.ReviewItemEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.data.repository.TransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device/emulator (Room needs real SQLite):  ./gradlew connectedDebugAndroidTest
 * Covers the three bugs that only show up in the SQL: duplicate SMS+notification rows,
 * a second real payment dropped as a "duplicate" review item, and CSV re-import doubling rows.
 */
@RunWith(AndroidJUnit4::class)
class DedupeAndImportTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TransactionRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = TransactionRepository(db, null)
    }

    @After
    fun tearDown() = db.close()

    private fun txn(ts: Long, bank: String?, account: String?, source: TransactionSource, payee: String = "Chai Point", amount: Long = 8_000L) =
        TransactionEntity(
            amount = amount, payee = payee, timestamp = ts, type = TransactionType.DEBIT, source = source,
            referenceId = null, rawContent = "x", bankName = bank, accountNumber = account
        )

    @Test
    fun smsAfterNotification_findsTheNotificationRow() = runBlocking {
        // UPI notification arrives first: bank and account are unknown.
        db.transactionDao().insertTransaction(txn(1_000_000L, null, null, TransactionSource.NOTIFICATION))

        val match = db.transactionDao().findMatchingTransaction(
            amount = 8_000.0, type = TransactionType.DEBIT,
            startTime = 700_000L, endTime = 1_300_000L, targetTime = 1_020_000L,
            bankName = "HDFC Bank", accountNumber = "1234"
        )
        assertNotNull(match)
    }

    @Test
    fun smsFromAnotherAccount_isNotMatched() = runBlocking {
        db.transactionDao().insertTransaction(txn(1_000_000L, "HDFC Bank", "1234", TransactionSource.SMS))
        val match = db.transactionDao().findMatchingTransaction(
            amount = 8_000.0, type = TransactionType.DEBIT,
            startTime = 700_000L, endTime = 1_300_000L, targetTime = 1_020_000L,
            bankName = "SBI", accountNumber = "9999"
        )
        assertNull(match)
    }

    @Test
    fun secondPaymentWithDifferentReference_isNotADuplicateReviewItem() = runBlocking {
        db.reviewItemDao().insertReviewItem(
            ReviewItemEntity(
                rawContent = "Rs 1500 debited", sender = "HDFCBK", timestamp = 1_000_000L, source = TransactionSource.SMS,
                extractedPartialAmount = 150_000L, type = TransactionType.DEBIT,
                bankName = "HDFC Bank", accountNumber = "1234", referenceId = "REF-A"
            )
        )
        val dao = db.reviewItemDao()
        val window = 300_000L
        val ts = 1_120_000L
        assertEquals(0, dao.countMatchingReviewItems(150_000L, TransactionType.DEBIT, ts - window, ts + window, "REF-B", "HDFC Bank", "1234"))
        assertEquals(1, dao.countMatchingReviewItems(150_000L, TransactionType.DEBIT, ts - window, ts + window, "REF-A", "HDFC Bank", "1234"))
        // The notification copy of the same payment has no reference or bank: still a duplicate.
        assertEquals(1, dao.countMatchingReviewItems(150_000L, TransactionType.DEBIT, ts - window, ts + window, null, null, null))
    }

    @Test
    fun importingTheSameCsvRowsTwice_addsThemOnce() = runBlocking {
        val rows = listOf(
            txn(1_700_000_123_000L, null, null, TransactionSource.MANUAL, payee = "Chai"),
            txn(1_700_000_999_000L, null, null, TransactionSource.MANUAL, payee = "Bus", amount = 1_500L)
        )
        assertEquals(2, repo.insertTransactionsBulk(rows))
        assertEquals(0, repo.insertTransactionsBulk(rows))
    }

    @Test
    fun twoIdenticalRowsInsideOneFile_bothImport() = runBlocking {
        val sameSecond = txn(1_700_000_123_000L, null, null, TransactionSource.MANUAL, payee = "Chai", amount = 2_000L)
        assertEquals(2, repo.insertTransactionsBulk(listOf(sameSecond, sameSecond)))
    }
}
