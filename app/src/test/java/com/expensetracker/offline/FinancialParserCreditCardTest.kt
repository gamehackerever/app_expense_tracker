package com.expensetracker.offline

import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.parser.FinancialParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for credit-card SMS formats. Needs only JUnit 4 on the unit-test classpath:
 *   testImplementation("junit:junit:4.13.2")
 * and, because SmsPatternLearner touches android.util.Log:
 *   testOptions { unitTests.isReturnDefaultValues = true }
 */
class FinancialParserCreditCardTest {
    private fun p(sender: String, text: String) = FinancialParser.parse(text, sender)

    @Test fun hdfcSpendWithAvlLmtIsCaptured() {
        val r = p("HDFCBK", "Spent Rs.1250.00 On HDFC Bank Card x1234 At SWIGGY On 2025-01-10:12:30:11. Avl Lmt: Rs.85000.00. Not You? Call 18002586161")
        assertTrue(r.isFinancial); assertEquals(125000L, r.amount)
        assertEquals(TransactionType.DEBIT, r.type); assertEquals("CREDIT_CARD", r.accountType)
    }
    @Test fun availableLimitIsExtractedInPaise() {
        val r = p("HDFCBK", "Spent Rs.1250.00 On HDFC Bank Card x1234 At SWIGGY On 2025-01-10:12:30:11. Avl Lmt: Rs.85,000.00. Not You? Call 18002586161")
        assertEquals(8_500_000L, r.availableLimit)
    }
    @Test fun bankAccountNeverGetsAnAvailableLimit() {
        val r = p("SBIINB", "Rs 200.00 debited from A/c XX1111 on 10-Jan-25 trf to RAJESH UPI Ref 123456789012. Avl bal Rs 5000")
        assertNull(r.availableLimit); assertEquals("BANK", r.accountType)
    }
    @Test fun upiOnCreditCardStaysCreditCard() {
        val r = p("HDFCBK", "Rs 499.00 debited from your HDFC Bank Credit Card XX1234 via UPI to NETFLIX on 10-01-25. Avl Lmt Rs 90000")
        assertEquals("CREDIT_CARD", r.accountType); assertFalse(r.excludeFromSpend)
    }
    @Test fun iciciUsedForTransactionIsDebit() {
        val r = p("ICICIB", "ICICI Bank Credit Card XX1234 has been used for a transaction of INR 2,450.00 on 10-Jan-25 at AMAZON. Avl Limit: INR 70,000.00.")
        assertEquals(TransactionType.DEBIT, r.type)
    }
    @Test fun cardPaymentReceivedIsExcludedTransfer() {
        val r = p("HDFCBK", "Payment of Rs. 5000.00 received towards your HDFC Bank Credit Card XX1234 on 12-Jan-25. Thank you.")
        assertTrue(r.isTransfer); assertTrue(r.excludeFromSpend); assertEquals("Credit Card Bill", r.payee)
    }
    @Test fun bankSideCardBillPaymentIsExcluded() {
        val r = p("HDFCBK", "Rs.5000.00 debited from A/c XX9876 on 12-Jan-25 towards Credit Card XX1234 payment. Avl bal Rs.12000")
        assertTrue(r.isTransfer); assertEquals("BANK", r.accountType)
    }
    @Test fun credDebitAloneIsNotBlanketTransfer() {
        // Pairing with a card credit (CardPaymentReconciler) decides this one; the parser must not guess.
        val r = p("AXISBK", "Rs 5000 debited from A/c XX9876 on 12-01-25 to CRED Club VPA cred.club@axisb. UPI Ref 501234567890. Avl bal Rs 12000")
        assertFalse(r.isTransfer); assertFalse(r.excludeFromSpend)
    }
    @Test fun statementAlertIsRejected() {
        assertFalse(p("ICICIB", "Your ICICI Bank Credit Card XX1234 statement: Total Due Rs 5,000.00, Min Due Rs 500").isFinancial)
    }
    @Test fun refundOnCardKeepsMerchantPayee() {
        val r = p("HDFCBK", "Refund of Rs 300.00 credited to your HDFC Bank Credit Card XX1234 from AMAZON on 12-Jan-25")
        assertEquals("Amazon", r.payee); assertTrue(r.excludeFromSpend)
    }

    @Test fun internationalSpendUsesForeignAmountNotAvailableLimit() {
        val p = FinancialParser.parse(
            "USD 2.12 spent on Kotak Credit Card 06-10-26 at PAYPAL *DEEPSEERWEA. Avl limit INR 38898.03 Not you? SMS CCLOST"
        )
        assertTrue(p.isFinancial)
        assertTrue(p.amountIsApproximate)
        assertEquals("USD", p.foreignCurrency)
        assertTrue("amount was ${p.amount}", p.amount!! in 15_000L..22_000L)
        assertEquals(3_889_803L, p.availableLimit)
    }

    @Test fun domesticCardSpendIgnoresAvailableLimit() {
        val p = FinancialParser.parse("Rs.1,250.00 spent on Kotak Credit Card XX1234 at SWIGGY on 06-10-26. Avl limit INR 38898.03")
        assertEquals(125_000L, p.amount)
        assertFalse(p.amountIsApproximate)
    }
}
