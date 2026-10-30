package com.expensetracker.offline

import com.expensetracker.offline.engine.importer.StatementFormat
import com.expensetracker.offline.engine.importer.StatementParser
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * Format regression tests for payment-statement import. The samples follow the layouts these exports
 * use; if a real file does not import, add a redacted copy of its text here as a new test first.
 */
class StatementParserTest {

    private fun ist(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(ts)

    @Test fun phonePeStatementMultiLineRows() {
        val text = """
            Transaction Statement for 9876543210
            Oct 01, 2025 - Oct 31, 2025
            Date Transaction Details Type Amount
            Oct 26, 2025
            03:35 pm
            Paid to MAIN CANTEEN
            Transaction ID T2510261535123456789
            UTR No. 529012345678
            Paid by XXXXXXXX1234
            DEBIT
            ₹80
            Oct 25, 2025
            11:02 am
            Received from NIDHI S
            Transaction ID T2510251102000000001
            Credited to XXXXXXXX1234
            CREDIT
            ₹1,500
        """.trimIndent()
        val r = StatementParser.parse(text)
        assertEquals(StatementFormat.PHONEPE, r.format)
        assertEquals(2, r.rows.size)
        val a = r.rows[0]
        assertTrue(a.isDebit); assertEquals(8000L, a.amountPaise); assertEquals("Main Canteen", a.payee)
        assertEquals("T2510261535123456789", a.referenceId)
        assertTrue(a.timeKnown)
        val b = r.rows[1]
        assertFalse(b.isDebit); assertEquals(150000L, b.amountPaise); assertEquals("Nidhi S", b.payee)
    }

    @Test fun phonePeStatementSingleLineRows() {
        val text = """
            Oct 26, 2025 Paid to ARM RESTAURANT DEBIT ₹260 Transaction ID T251026153512345
            Oct 24, 2025 Paid to AUTO DEBIT ₹30
        """.trimIndent()
        val r = StatementParser.parse(text)
        assertEquals(2, r.rows.size)
        assertEquals("Arm Restaurant", r.rows[0].payee); assertEquals(26000L, r.rows[0].amountPaise)
        assertEquals(3000L, r.rows[1].amountPaise)
    }

    @Test fun googlePayTakeoutHtml() {
        val html = """<html><body><div class="content-cell">Paid ₹80.00 to Main Canteen<br>Oct 26, 2025, 3:35:11 PM IST<br></div>
            <div class="content-cell">Received ₹500.00 from Nidhi<br>Oct 25, 2025, 11:02:00 AM IST<br></div>
            <div class="content-cell">Paid ₹1,200.00 to HDFC Bank Credit Card using Bank Account SBI 1234<br>Oct 20, 2025, 9:00:00 PM IST<br></div></body></html>"""
        val r = StatementParser.parse(html)
        assertEquals(StatementFormat.GOOGLE_PAY, r.format)
        assertEquals(3, r.rows.size)
        assertTrue(r.rows[0].isDebit); assertEquals(8000L, r.rows[0].amountPaise)
        assertEquals("2025-10-26 15:35", ist(r.rows[0].timestamp))
        assertFalse(r.rows[1].isDebit)
        assertEquals("HDFC Bank Credit Card", r.rows[2].payee)
    }

    @Test fun googlePayTakeoutJson() {
        val json = """[{"header":"Google Pay","title":"Paid ₹80.00 to Main Canteen","time":"2025-10-26T10:05:11.123Z"},
            {"header":"Google Pay","title":"Received ₹500.00 from Nidhi","time":"2025-10-25T05:32:00.000Z"}]"""
        val r = StatementParser.parse(json)
        assertEquals(StatementFormat.GOOGLE_PAY, r.format)
        assertEquals(2, r.rows.size)
        assertEquals("2025-10-26 15:35", ist(r.rows[0].timestamp))
    }

    @Test fun bankCsvWithDebitCreditColumns() {
        val csv = """
            Account Statement,,,,
            Txn Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
            26/10/2025,UPI/DR/529012345678/MAIN CANTEEN/YESB/q123@ybl/UPI,529012345678,80.00,,5000.00
            25/10/2025,UPI/CR/529012345679/NIDHI S/SBIN/n@oksbi/UPI,529012345679,,1500.00,6580.00
        """.trimIndent()
        val r = StatementParser.parse(csv)
        assertEquals(StatementFormat.CSV, r.format)
        assertEquals(2, r.rows.size)
        assertTrue(r.rows[0].isDebit); assertEquals(8000L, r.rows[0].amountPaise)
        assertEquals("Main Canteen", r.rows[0].payee); assertEquals("529012345678", r.rows[0].referenceId)
        assertFalse(r.rows[0].timeKnown)
        assertFalse(r.rows[1].isDebit); assertEquals(150000L, r.rows[1].amountPaise)
    }

    @Test fun csvWithSingleAmountAndTypeColumn() {
        val csv = "Date,Description,Amount,Type\n2025-10-26,Swiggy order,\"1,250.50\",DEBIT\n2025-10-25,Refund Swiggy,300,CREDIT\n"
        val r = StatementParser.parse(csv)
        assertEquals(2, r.rows.size)
        assertEquals(125050L, r.rows[0].amountPaise); assertTrue(r.rows[0].isDebit)
        assertFalse(r.rows[1].isDebit)
    }

    @Test fun unrelatedTextIsUnknown() {
        val r = StatementParser.parse("Hello, this is not a statement.\nNothing to see here.")
        assertEquals(StatementFormat.UNKNOWN, r.format)
        assertTrue(r.rows.isEmpty())
    }

    @Test fun phonePeTimeAtStartOfFollowingLine() {
        val text = """
            Sep 06, 2026 Paid to M A SUPER MARKET DEBIT ₹339
            03:35 pm Transaction ID T2609061535123456
            Sep 05, 2026 Received from PRAKASAN VACHALI CREDIT ₹3,500
            09:10 am Transaction ID T2609050910000002
        """.trimIndent()
        val r = StatementParser.parse(text)
        assertEquals(2, r.rows.size)
        assertTrue(r.rows.all { it.timeKnown })
        assertEquals("T2609061535123456", r.rows[0].referenceId)
        assertEquals("M A Super Market", r.rows[0].payee)
        assertFalse(r.rows[1].isDebit)
    }
}
