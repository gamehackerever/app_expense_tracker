package com.expensetracker.offline

import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.util.SbiPdfParserUtil
import org.junit.Assert.*
import org.junit.Test

class SbiPdfParserTest {

    @Test
    fun testExtractAccountNumber() {
        val sampleHeader = """
            STATE BANK OF INDIA
            Account Name : Mr. John Doe
            Account Number : 00000012345678901
            Branch : KORAMANGALA
        """.trimIndent()

        val accNum = SbiPdfParserUtil.extractAccountNumber(sampleHeader)
        assertEquals("00000012345678901", accNum)
    }

    @Test
    fun testParseSbiStatementText() {
        val statementText = """
            State Bank of India
            Account Number : 00000038472910293
            Txn Date    Value Date  Description    Ref No./Cheque No.  Debit   Credit  Balance
            01 Jan 2024 01 Jan 2024 TO TRANSFER-UPI/DR/400123456789/SWIGGY/ICIC/swiggy@icici/UPI TRANSFER TO 400123456789 250.00 45,230.50
            15/04/2024 15/04/2024 TO TRANSFER-UPI/DR/410628374921/AMAZON/HDFC/pay@hdfc 410628374921 1,499.00 43,731.50
            16/04/2024 16/04/2024 BY TRANSFER-UPI/CR/410738491029/JOHN DOE/SBI/john@sbi 410738491029 5,000.00 48,731.50
            20/04/2024 20/04/2024 ATM WDL-ATM CASH KORAMANGALA 2,000.00 46,731.50
            22/04/2024 22/04/2024 INT.PD:01-01-2024 to 31-03-2024 125.00 46,856.50
            02-05-2024 02-05-2024 POS 123456 STARBUCKS COFFEE 350.00 46,506.50
        """.trimIndent()

        val rows = SbiPdfParserUtil.parseSbiStatementText(statementText)
        assertEquals(6, rows.size)

        // Row 1: Swiggy Debit
        val r1 = rows[0]
        assertEquals(TransactionType.DEBIT, r1.type)
        assertEquals(25000L, r1.amountPaise)
        assertEquals("SWIGGY", r1.payee)
        assertEquals("400123456789", r1.refNo)
        assertEquals(4523050L, r1.balancePaise)

        // Row 2: Amazon Debit
        val r2 = rows[1]
        assertEquals(TransactionType.DEBIT, r2.type)
        assertEquals(149900L, r2.amountPaise)
        assertEquals("AMAZON", r2.payee)
        assertEquals("410628374921", r2.refNo)

        // Row 3: Credit UPI
        val r3 = rows[2]
        assertEquals(TransactionType.CREDIT, r3.type)
        assertEquals(500000L, r3.amountPaise)
        assertEquals("JOHN DOE", r3.payee)
        assertEquals("410738491029", r3.refNo)

        // Row 4: ATM Cash
        val r4 = rows[3]
        assertEquals(TransactionType.DEBIT, r4.type)
        assertEquals(200000L, r4.amountPaise)
        assertEquals("ATM Cash Withdrawal", r4.payee)

        // Row 5: Interest Credit
        val r5 = rows[4]
        assertEquals(TransactionType.CREDIT, r5.type)
        assertEquals(12500L, r5.amountPaise)
        assertEquals("Interest Credit", r5.payee)

        // Row 6: POS Spend
        val r6 = rows[5]
        assertEquals(TransactionType.DEBIT, r6.type)
        assertEquals(35000L, r6.amountPaise)
        assertEquals("STARBUCKS COFFEE", r6.payee)
    }

    @Test
    fun testMultiLineWrappedDescription() {
        val text = """
            01 Jan 2024 01 Jan 2024 TO TRANSFER-UPI/DR/400123456789/VERY LONG
            MERCHANT NAME BANGALORE/ICIC/vpa@icici/UPI 250.00 45,230.50
        """.trimIndent()

        val rows = SbiPdfParserUtil.parseSbiStatementText(text)
        assertEquals(1, rows.size)
        assertEquals(TransactionType.DEBIT, rows[0].type)
        assertEquals(25000L, rows[0].amountPaise)
        assertEquals("400123456789", rows[0].refNo)
    }
}
