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

    @Test
    fun testCanaraBankStatementParsing() {
        val header = """
            CANARA BANK
            Account Statement
            A/c No: 1234 101 012345
            Customer ID: 987654321
        """.trimIndent()

        val accNum = SbiPdfParserUtil.extractAccountNumber(header)
        assertEquals("1234101012345", accNum)
        assertEquals("Canara Bank", SbiPdfParserUtil.detectBankName(header))

        val statementText = """
            CANARA BANK
            A/c No: 1234101012345
            Date Description Ref No. Debit Credit Balance
            10/05/2024 UPI/413524123456/DR/SWIGGY/CNRB/swiggy@icici/Order123 450.00 12,345.00
            11/05/2024 UPI/P2M/413625234567/AMAZON_PAY/CNRB/amazon@apl 1,299.00 11,046.00
            12/05/2024 UPI/P2A/413726345678/RAHUL_SHARMA/CNRB/rahul@ybl 500.00 10,546.00
            13/05/2024 BIL/ONL/0023456789/BESCOM_ELECTRICITY 850.00 9,696.00
            14/05/2024 UPI/413827456789/CR/JOHN_DOE/CNRB/john@sbi 2,000.00 11,696.00
        """.trimIndent()

        val rows = SbiPdfParserUtil.parseSbiStatementText(statementText)
        assertEquals(5, rows.size)

        // Row 1: Swiggy
        assertEquals(TransactionType.DEBIT, rows[0].type)
        assertEquals(45000L, rows[0].amountPaise)
        assertEquals("SWIGGY", rows[0].payee)
        assertEquals("413524123456", rows[0].refNo)
        assertEquals(1234500L, rows[0].balancePaise)
        assertEquals("Order123", rows[0].note)

        // Row 2: Amazon Pay (P2M)
        assertEquals(TransactionType.DEBIT, rows[1].type)
        assertEquals(129900L, rows[1].amountPaise)
        assertEquals("AMAZON PAY", rows[1].payee)
        assertEquals("413625234567", rows[1].refNo)

        // Row 3: Rahul Sharma (P2A peer transfer)
        assertEquals(TransactionType.DEBIT, rows[2].type)
        assertEquals(50000L, rows[2].amountPaise)
        assertEquals("RAHUL SHARMA", rows[2].payee)
        assertEquals("413726345678", rows[2].refNo)

        // Row 4: Bescom Electricity (Bill)
        assertEquals(TransactionType.DEBIT, rows[3].type)
        assertEquals(85000L, rows[3].amountPaise)
        assertTrue(rows[3].payee.contains("BESCOM", ignoreCase = true))

        // Row 5: Credit
        assertEquals(TransactionType.CREDIT, rows[4].type)
        assertEquals(200000L, rows[4].amountPaise)
        assertEquals("JOHN DOE", rows[4].payee)
        assertEquals("413827456789", rows[4].refNo)
    }

    @Test
    fun testBookMyShowAndPenStatementCategorization() {
        val statement = """
            CANARA BANK
            A/c No: 1234101012345
            Date Description Ref No. Debit Credit Balance
            15/05/2024 UPI/413928567890/DR/BOOKMYSHOW/CNRB/bookmyshow@icici/tickets 500.00 11,196.00
            16/05/2024 UPI/414029678901/DR/RAJU/CNRB/raju@okaxis/pen 20.00 11,176.00
            17/05/2024 POS 00123456 BIGTREE ENTERTAINMENT MUMBAI 650.00 10,526.00
            18/05/2024 UPI/414130789012/DR/SRI_KRISHNA_STATIONERY/CNRB/stationery@sbi 150.00 10,376.00
        """.trimIndent()

        val rows = SbiPdfParserUtil.parseSbiStatementText(statement)
        assertEquals(4, rows.size)

        // Row 1: BookMyShow -> Entertainment
        assertEquals("BOOKMYSHOW", rows[0].payee)
        assertEquals("tickets", rows[0].note)
        val cat1 = com.expensetracker.offline.engine.categorizer.CategoryClassifier.classifySync(rows[0].payee, rows[0].description, rows[0].note)
        assertEquals("Entertainment", cat1)

        // Row 2: Pen -> Education
        assertEquals("RAJU", rows[1].payee)
        assertEquals("pen", rows[1].note)
        val cat2 = com.expensetracker.offline.engine.categorizer.CategoryClassifier.classifySync(rows[1].payee, rows[1].description, rows[1].note)
        assertEquals("Education", cat2)

        // Row 3: Bigtree Entertainment -> Entertainment
        assertEquals("BIGTREE ENTERTAINMENT MUMBAI", rows[2].payee)
        val cat3 = com.expensetracker.offline.engine.categorizer.CategoryClassifier.classifySync(rows[2].payee, rows[2].description, rows[2].note)
        assertEquals("Entertainment", cat3)

        // Row 4: Stationery -> Education
        assertEquals("SRI KRISHNA STATIONERY", rows[3].payee)
        val cat4 = com.expensetracker.offline.engine.categorizer.CategoryClassifier.classifySync(rows[3].payee, rows[3].description, rows[3].note)
        assertEquals("Education", cat4)

        // Verify note extraction from rawLine
        assertEquals("pen", SbiPdfParserUtil.extractNoteFromRawLine(rows[1].rawLine))
        assertEquals("tickets", SbiPdfParserUtil.extractNoteFromRawLine(rows[0].rawLine))
    }
}
