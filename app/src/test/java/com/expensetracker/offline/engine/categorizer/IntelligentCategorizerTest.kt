package com.expensetracker.offline.engine.categorizer

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class IntelligentCategorizerTest {

    // ────────────────────── Layer 1: MCC & Gateway Decoding ──────────────────────

    @Test
    fun testMccCodeExtractionAndCategorization() {
        val r1 = MccAndGatewayClassifier.extractMccMatch("POS 123456 MCC: 5812 RESTAURANT BANGALORE")
        assertNotNull(r1)
        assertEquals("Food & Dining", r1?.category)
        assertEquals("5812", r1?.mccCode)

        val r2 = MccAndGatewayClassifier.extractMccMatch("POS 654321 MCC 7832 INOX CINEMAS MUMBAI")
        assertNotNull(r2)
        assertEquals("Entertainment", r2?.category)
        assertEquals("7832", r2?.mccCode)

        val r3 = MccAndGatewayClassifier.extractMccMatch("TXN REF MCC:8299 VIDYALAYA SCHOOL")
        assertNotNull(r3)
        assertEquals("Education", r3?.category)
        assertEquals("8299", r3?.mccCode)

        val r4 = MccAndGatewayClassifier.extractMccMatch("DEBIT MCC-5541 PETROL BUNK")
        assertNotNull(r4)
        assertEquals("Fuel & Fastag", r4?.category)
        assertEquals("5541", r4?.mccCode)

        val r5 = MccAndGatewayClassifier.extractMccMatch("CARD PURCHASE MCC: 5411 SUPERMARKET")
        assertNotNull(r5)
        assertEquals("Groceries", r5?.category)
        assertEquals("5411", r5?.mccCode)
    }

    @Test
    fun testPaymentGatewayVpaDecoding() {
        val gw1 = MccAndGatewayClassifier.parseGatewayVpa("bookmyshow@razorpay")
        assertNotNull(gw1)
        assertEquals("bookmyshow", gw1?.first)
        assertTrue(gw1?.second == true)

        val gw2 = MccAndGatewayClassifier.parseGatewayVpa("swiggy@billdesk")
        assertNotNull(gw2)
        assertEquals("swiggy", gw2?.first)
        assertTrue(gw2?.second == true)

        val personal = MccAndGatewayClassifier.parseGatewayVpa("rahul@okaxis")
        assertNotNull(personal)
        assertEquals("rahul", personal?.first)
        assertFalse(personal?.second == true) // Not a commercial payment gateway
    }

    // ────────────────────── Layer 2: Contextual & Behavioral Signals ──────────────────────

    @Test
    fun testTelcoRechargePlanMatching() {
        // ₹299 with Jio text -> Recharge & Telecom
        val d1 = ContextualClassifier.evaluate(
            amountPaise = 29900L,
            timestamp = System.currentTimeMillis(),
            payee = "Jio Prepaid",
            note = null,
            rawContent = "Paid Rs 299 for Jio mobile recharge"
        )
        assertNotNull(d1)
        assertEquals("Recharge & Telecom", d1?.category)
        assertTrue(d1?.confidence ?: 0f >= 0.85f)

        // ₹666 with Airtel text -> Recharge & Telecom
        val d2 = ContextualClassifier.evaluate(
            amountPaise = 66600L,
            timestamp = System.currentTimeMillis(),
            payee = "UPI Payment",
            note = "airtel recharge",
            rawContent = "UPI to Airtel"
        )
        assertNotNull(d2)
        assertEquals("Recharge & Telecom", d2?.category)
    }

    @Test
    fun testMealTimeWindows() {
        // Morning breakfast at 8:30 AM (₹60)
        val morningCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
        }
        val d1 = ContextualClassifier.evaluate(
            amountPaise = 6000L,
            timestamp = morningCal.timeInMillis,
            payee = "Chai Stall",
            note = null,
            rawContent = "Paid at chai tea stall"
        )
        assertNotNull(d1)
        assertEquals("Food & Dining", d1?.category)

        // Afternoon lunch at 1:15 PM (₹220)
        val lunchCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 13)
            set(Calendar.MINUTE, 15)
        }
        val d2 = ContextualClassifier.evaluate(
            amountPaise = 22000L,
            timestamp = lunchCal.timeInMillis,
            payee = "Meals Dhaba",
            note = null,
            rawContent = "Lunch meals at dhaba"
        )
        assertNotNull(d2)
        assertEquals("Food & Dining", d2?.category)
    }

    @Test
    fun testWeekendEntertainmentWindow() {
        // Saturday 8:00 PM (₹600) with movie tickets
        val satCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
            set(Calendar.HOUR_OF_DAY, 20)
        }
        val d = ContextualClassifier.evaluate(
            amountPaise = 60000L,
            timestamp = satCal.timeInMillis,
            payee = "Cinema Multiplex",
            note = "tickets",
            rawContent = "Movie show tickets"
        )
        assertNotNull(d)
        assertEquals("Entertainment", d?.category)
    }

    @Test
    fun testMonthStartRentWindow() {
        // 3rd of month, ₹18,000 with maintenance/society
        val rentCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 3)
        }
        val d = ContextualClassifier.evaluate(
            amountPaise = 1800000L,
            timestamp = rentCal.timeInMillis,
            payee = "Society Maintenance",
            note = "flat rent",
            rawContent = "Flat maintenance payment"
        )
        assertNotNull(d)
        assertEquals("Rent & Housing", d?.category)
    }

    @Test
    fun testDaytimeMicroStationeryWindow() {
        // 2:00 PM, ₹20 with pen/xerox
        val dayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
        }
        val d = ContextualClassifier.evaluate(
            amountPaise = 2000L,
            timestamp = dayCal.timeInMillis,
            payee = "Student Centre",
            note = "pen",
            rawContent = "Paid for pen and xerox"
        )
        assertNotNull(d)
        assertEquals("Education", d?.category)
    }

    // ────────────────────── Layer 3: Semantic Subword Vector Centroid Engine ──────────────────────

    @Test
    fun testSemanticSubwordCentroidClassifier() {
        // Novel compound for Education: "fountainpen"
        val m1 = SemanticCentroidClassifier.classifySemantic("fountainpen")
        assertNotNull(m1)
        assertEquals("Education", m1?.category)

        // Novel compound for Education: "pencilbox"
        val m2 = SemanticCentroidClassifier.classifySemantic("pencilbox")
        assertNotNull(m2)
        assertEquals("Education", m2?.category)

        // Novel term for Entertainment: "multiplexes"
        val m3 = SemanticCentroidClassifier.classifySemantic("multiplexes")
        assertNotNull(m3)
        assertEquals("Entertainment", m3?.category)
    }

    // ────────────────────── Layer 4: Multi-Factor Ensemble in CategoryClassifier ──────────────────────

    @Test
    fun testUnifiedDetailedDecisionEngine() {
        // 1. MCC code in raw narration takes high confidence
        val dec1 = CategoryClassifier.classifyDetailedSync(
            payee = "Merchant 123",
            rawContent = "CARD 0012 MCC: 7832 LEISURE",
            note = null
        )
        assertEquals("Entertainment", dec1.category)
        assertEquals(CategoryClassifier.DecisionSource.MCC_CODE, dec1.source)
        assertTrue(dec1.confidence >= 0.95f)

        // 2. Gateway decoding (e.g. bookmyshow@razorpay)
        val dec2 = CategoryClassifier.classifyDetailedSync(
            payee = "bookmyshow@razorpay",
            rawContent = "UPI/412345/bookmyshow@razorpay",
            note = null
        )
        assertEquals("Entertainment", dec2.category)
        assertEquals(CategoryClassifier.DecisionSource.MCC_GATEWAY, dec2.source)

        // 3. User note highest priority
        val dec3 = CategoryClassifier.classifyDetailedSync(
            payee = "Random Person",
            rawContent = "Paid to person",
            note = "pen"
        )
        assertEquals("Education", dec3.category)
        assertEquals(CategoryClassifier.DecisionSource.USER_NOTE, dec3.source)

        // 4. Contextual Telco plan
        val dec4 = CategoryClassifier.classifyDetailedSync(
            payee = "UPI Payment",
            rawContent = "Recharge plan",
            note = null,
            amountPaise = 29900L,
            timestamp = System.currentTimeMillis()
        )
        assertEquals("Recharge & Telecom", dec4.category)
        assertEquals(CategoryClassifier.DecisionSource.CONTEXTUAL_BEHAVIORAL, dec4.source)
    }
}
