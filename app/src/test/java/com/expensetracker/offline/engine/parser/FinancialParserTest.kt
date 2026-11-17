package com.expensetracker.offline.engine.parser

import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.ml.SmsPatternLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real bank SMS / UPI notification shapes. Amounts are PAISE. */
class FinancialParserTest {

    @Before
    fun resetLearner() {
        SmsPatternLearner.clearAllLearnedPatterns()
    }

    private fun sms(text: String, header: String) = FinancialParser.parse(text, header, TransactionSource.SMS)
    private fun notif(text: String, app: String) = FinancialParser.parse(text, app, TransactionSource.NOTIFICATION)

    @Test
    fun plainUpiDebit_readsEverything() {
        val p = sms("Rs.500.00 debited from A/c XX1234 on 05-10-26 to VPA zomato@icici UPI Ref 123456789012. Avl Bal Rs 15,000.00", "HDFCBK")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(50_000L, p.amount)
        assertEquals("Zomato", p.payee)
        assertEquals("HDFC Bank", p.bankName)
        assertEquals("1234", p.accountNumber)
        assertEquals(1_500_000L, p.balance)
        assertEquals("123456789012", p.referenceId)
    }

    @Test
    fun indianLakhFormat_isReadCorrectly() {
        val p = sms("INR 1,25,000.00 debited from A/c XX1234. Avl bal INR 2,00,000.00", "AX-HDFCBK")
        assertEquals(12_500_000L, p.amount)
        assertEquals(20_000_000L, p.balance)
    }

    @Test
    fun balanceIsNeverMistakenForTheAmount() {
        val p = sms("Avl Bal Rs 5,000.00. Rs 100 spent on card ending 4321 at SWIGGY", "ICICIB")
        assertEquals(10_000L, p.amount)
        assertEquals(500_000L, p.balance)
        assertEquals(TransactionType.DEBIT, p.type)
    }

    @Test
    fun salaryCredit() {
        val p = sms("Rs 45000.00 credited to A/c XX1234 on 01-10-26. Avl Bal Rs 60,000.00", "SBIINB")
        assertEquals(TransactionType.CREDIT, p.type)
        assertEquals(4_500_000L, p.amount)
        assertEquals("SBI", p.bankName)
    }

    @Test
    fun refundIsCreditAndExcludedFromSpend() {
        val p = sms("Rs 300 refunded to your A/c XX1234 for order 77", "AX-HDFCBK")
        assertEquals(TransactionType.CREDIT, p.type)
        assertTrue(p.excludeFromSpend)
    }

    @Test
    fun creditCardBillPaymentIsExcludedFromSpend() {
        val p = sms("Rs 12000 debited from A/c XX1234 towards credit card bill XX4321", "HDFCBK")
        assertEquals(TransactionType.DEBIT, p.type)
        assertTrue(p.excludeFromSpend)
    }

    @Test
    fun creditCardPurchaseCountsAsSpend() {
        val p = sms("Rs 2500 spent on HDFC Credit Card XX4321 at AMAZON on 04-10-26", "HDFCBK")
        assertEquals(TransactionType.DEBIT, p.type)
        assertFalse(p.excludeFromSpend)
    }

    @Test
    fun transferWithDebitAndCreditVerbs_usesTheSenderAccountDirection() {
        val p = sms("Rs 500 debited from your A/c and credited to A/c XX9999 of Ravi. UPI Ref 123456789012", "AX-SBIINB")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
    }

    @Test
    fun otpFailedAndFutureMandateAreRejected() {
        assertFalse(sms("123456 is your OTP for txn of Rs 500 at Amazon. Do not share.", "AX-HDFCBK").isFinancial)
        assertFalse(notif("Your payment of Rs 500 failed. Amount will be refunded", "PhonePe").isFinancial)
        assertFalse(sms("Rs 999 will be debited from your A/c XX1234 on 10-10-26 for Netflix", "HDFCBK").isFinancial)
    }

    @Test
    fun scheduledAutopaySmsAndUpcomingNotificationAreRejected() {
        val bankReminder = "Dear SRINAND E K UPI auto pay for SPOTIFY INDIA PVT LTD debit of Rs 69.00 is scheduled on 16/11/2026, .Please ensure sufficient balance in your account.-South Indian Bank."
        assertFalse(sms(bankReminder, "South Indian Bank").isFinancial)

        val notificationReminder = notif(
            "Rs 69.00 · scheduled for 16/11/2026",
            "Upcoming Autopay Payment To Spotify India Pvt"
        )
        assertFalse(notificationReminder.isFinancial)

        assertFalse(
            sms("UPI mandate for Spotify India of Rs 69.00 will be presented on 16/11/2026", "SIBSMS").isFinancial
        )
    }

    @Test
    fun completedAutopayDebitIsStillRecognized() {
        val p = sms(
            "UPI AutoPay of Rs 69.00 was debited from A/c XX1234 on 16/11/2026 to Spotify India Pvt Ltd. UPI Ref 123456789012",
            "SIBSMS"
        )

        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(6_900L, p.amount)
    }

    @Test
    fun successfulGPayAutopayNotificationUsesTheMerchantInsteadOfItsTitle() {
        val p = FinancialParser.parse(
            rawText = "Payment to Vi was successful Payment for Autopay of ₹398.00 to Vi was successful. Tap to view.",
            title = "Payment to Vi was successful",
            source = TransactionSource.NOTIFICATION
        )

        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(39_800L, p.amount)
        assertEquals("Vi", p.payee)
    }

    @Test
    fun promoAndBalanceEnquiryAreRejected() {
        assertFalse(sms("Recharge now Rs 299 plan 28 days validity unlimited data", "JIOINF").isFinancial)
        assertFalse(sms("Your A/c XX1234 balance is Rs 15000.00 as on 05-10-26", "HDFCBK").isFinancial)
    }

    @Test
    fun reversal_isKeptAsMoneyBack_notDropped() {
        val p = sms("Rs 300 reversed to your A/c XX1234", "AX-HDFCBK")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.CREDIT, p.type)
        assertEquals(30_000L, p.amount)
    }

    @Test
    fun failedPaymentThatWasReversedIsStillRejected() {
        assertFalse(sms("Your payment of Rs 500 failed. The amount has been reversed to your A/c XX1234", "AX-HDFCBK").isFinancial)
    }

    @Test
    fun upiAppNotification_hasNoBank_soDedupeMustAcceptAnUnknownBank() {
        val p = notif("Google Pay Paid ₹80 to Chai Point", "Google Pay")
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(8_000L, p.amount)
        assertEquals("Chai Point", p.payee)
        assertNull(p.bankName)
    }

    @Test
    fun paidYouNotification_isCreditFromThatPerson() {
        val p = notif("Ravi paid you Rs 200.00 via UPI", "Google Pay")
        assertEquals(TransactionType.CREDIT, p.type)
        assertEquals(20_000L, p.amount)
        assertEquals("Ravi", p.payee)
    }

    @Test
    fun discardedReviewTemplate_isLearnedAndBlockedNextTime() {
        val sample1 = "Dear Customer, Rs 1500 cashback voucher is waiting on your A/c XX1234 debited card campaign."
        val sample2 = "Dear Customer, Rs 2500 cashback voucher is waiting on your A/c XX1234 debited card campaign."
        assertTrue(sms(sample1, "AX-HDFCBK").isFinancial)

        SmsPatternLearner.learnDiscardedReview("AX-HDFCBK", sample1)
        assertFalse(sms(sample2, "VM-HDFCBK").isFinancial)
    }

    @Test
    fun approvedReviewTemplate_learnsTypeAndPayeeForAmbiguousSms() {
        val msg1 = "Rs 500 debit and credit adjustment to Ravi. UPI Ref 123456789012"
        val msg2 = "Rs 850 debit and credit adjustment to Suresh. UPI Ref 987654321098"

        // Before approval, type is ambiguous (null) so it goes to review
        val initial = sms(msg1, "AX-SBIINB")
        assertNull(initial.type)

        // User approves msg1 in Review Queue as DEBIT to Ravi
        SmsPatternLearner.learnApprovedReview(
            sender = "AX-SBIINB",
            rawContent = msg1,
            resolvedPayee = "Ravi",
            resolvedCategory = "Transfer",
            resolvedType = TransactionType.DEBIT,
            resolvedBankName = "SBI"
        )

        // Next time msg2 arrives with the same structure, type is automatically DEBIT and payee is Suresh!
        val afterLearning = sms(msg2, "AX-SBIINB")
        assertTrue(afterLearning.isFinancial)
        assertEquals(TransactionType.DEBIT, afterLearning.type)
        assertEquals("Suresh", afterLearning.payee)
    }

    @Test
    fun googlePayNotification_paidToMerchant_extractsCleanPayeeAndAmount() {
        val p = notif("Paid to Starbucks ₹250.00 using State Bank of India ••••1234", "Paid to Starbucks")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(25_000L, p.amount)
        assertEquals("Starbucks", p.payee)
        assertEquals("SBI", p.bankName)
        assertEquals("1234", p.accountNumber)
    }

    @Test
    fun phonePeNotification_paymentOfAmount_extractsCleanPayee() {
        val p = notif("Payment of ₹350 to Swiggy was successful using Canara Bank - 1234", "Payment Successful")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(35_000L, p.amount)
        assertEquals("Swiggy", p.payee)
        assertEquals("Canara Bank", p.bankName)
        assertEquals("1234", p.accountNumber)
    }

    @Test
    fun canaraAi1Notification_towardsUpiSlash_extractsPayeeName() {
        val p = notif(
            "Your A/C ending 1234 has been debited for INR 250.00 on 17-11-2026 towards UPI/432109876543/TEA_STALL/CNRB. Avl Bal: INR 12,345.00",
            "Canara ai1"
        )
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(25_000L, p.amount)
        assertEquals("Tea Stall", p.payee)
        assertEquals("Canara Bank", p.bankName)
        assertEquals("1234", p.accountNumber)
        assertEquals(1_234_500L, p.balance)
    }

    @Test
    fun paytmNotification_moneySent_extractsPayee() {
        val p = notif("Paid ₹120 to Chai Point from Paytm Payments Bank", "Money Sent")
        assertTrue(p.isFinancial)
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(12_000L, p.amount)
        assertEquals("Chai Point", p.payee)
    }
}
