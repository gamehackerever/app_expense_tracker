package com.expensetracker.offline

import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.parser.FinancialParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for ICICI / SBI (and a few other) alert layouts that used to come out with no
 * debit/credit direction because the SMS names both a debit and a credit.
 */
class FinancialParserIciciSbiTest {
    private fun p(sender: String, text: String) = FinancialParser.parse(text, sender)

    @Test fun iciciDebitWithMerchantCreditedLater() {
        val r = p("AX-ICICIB", "ICICI Bank Acct XX123 debited for Rs 500.00 on 12-Oct-25; SWIGGY credited. UPI:529012345678. Call 18002662 for dispute.")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals(50000L, r.amount); assertEquals("Swiggy", r.payee)
    }
    @Test fun iciciImpsDebitNamesBothAccounts() {
        val r = p("AX-ICICIB", "ICICI Bank Acct XX123 debited with Rs 2,500.00 on 12-Oct-25 & Acct XX456 credited. IMPS: 529012345678. Call 18002662 for dispute.")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals(250000L, r.amount)
    }
    @Test fun iciciInfoLineKeepsMerchantNotReference() {
        val r = p("AX-ICICIB", "ICICI Bank Acct XX123 debited Rs. 120.00 on 12-Oct-25 Info: UPI-529012345678-MAIN CANTEEN. Avl Bal Rs 8,000.50.")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals("Main Canteen", r.payee)
    }
    @Test fun iciciCreditIsStillCredit() {
        val r = p("AX-ICICIB", "Dear Customer, Acct XX123 is credited with Rs 1,000.00 on 12-Oct-25 from NIDHI S. UPI:529012345678-ICICI Bank.")
        assertEquals(TransactionType.CREDIT, r.type)
    }
    @Test fun sbiHasBeenDebitedAndCreditedToMerchant() {
        val r = p("VM-SBIUPI", "Dear SBI User, your A/C X1234 has been debited by Rs.80 on 12Oct25 and credited to MAIN CANTEEN (UPI Ref no 529012345678).")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals(8000L, r.amount); assertEquals("Main Canteen", r.payee)
    }
    @Test fun sbiImpsAccountNoWithDotIsDebit() {
        val r = p("AX-SBIPSG", "Your a/c no. XXXXXXXX1234 is debited for Rs.150.00 on 12-10-25 and a/c is credited (IMPS Ref no 529012345678).")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals(15000L, r.amount)
    }
    @Test fun atmWithdrawalGetsAReadablePayee() {
        val r = p("VM-SBIINB", "Dear Customer, A/C X1234 debited INR 200.00 on 12/10/25 by ATM WDL. Avl Bal INR 5,000.00")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals("Atm Withdrawal", r.payee)
    }
    @Test fun boiVerbBeforeAccountIsDebit() {
        val r = p("BOIIND", "Rs.250.00 debited A/cXX1234 and credited to MAIN CANTEEN via UPI Ref No 529012345678 on 12Oct25. BOI")
        assertEquals(TransactionType.DEBIT, r.type)
    }
    @Test fun axisThankYouForUsingCardIsASpend() {
        val r = p("AXISBK", "Thank you for using Axis Bank Card no. XX1234 for INR 2,300.00 at AMAZON on 12-10-25 15:20.")
        assertTrue(r.isFinancial); assertEquals(TransactionType.DEBIT, r.type); assertEquals(230000L, r.amount)
    }
    @Test fun axisUpiSlashStopsAtSmsBlock() {
        val r = p("AXISBK", "INR 300.00 debited A/c no. XX1234 12-10-25 15:20:11 UPI/P2M/529012345678/MAIN CANTEEN SMS BLOCK UPI to 18002026")
        assertEquals("Main Canteen", r.payee)
    }
    @Test fun kotakSentFromToVpa() {
        val r = p("KOTAKB", "Sent Rs.120.00 from Kotak Bank AC X1234 to nidhi@ybl on 12-10-25.UPI Ref 529012345678. Not you? Call 1800 274 0110")
        assertEquals(TransactionType.DEBIT, r.type); assertEquals("Nidhi", r.payee)
    }
    @Test fun otpsAndDeclinedAreStillIgnored() {
        assertFalse(p("AX-ICICIB", "Your OTP is 123456 for txn of Rs 500. Do not share.").isFinancial)
        assertFalse(p("AXISBK", "Your transaction of Rs 500 on card XX1234 has been declined due to insufficient funds.").isFinancial)
    }
}
