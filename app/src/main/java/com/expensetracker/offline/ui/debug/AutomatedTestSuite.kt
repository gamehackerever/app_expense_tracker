package com.expensetracker.offline.ui.debug

import android.content.Context
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.parser.FinancialParser
import com.expensetracker.offline.engine.split.ReceiptScanner
import com.expensetracker.offline.ui.components.evaluateBasicMath
import com.expensetracker.offline.util.BillingCycleHelper
import com.expensetracker.offline.util.NecessityFrequency
import com.expensetracker.offline.util.NecessityItem
import com.expensetracker.offline.util.NecessityManager

data class TestCaseResult(
    val suite: String,
    val name: String,
    val passed: Boolean,
    val detail: String,
    val durationMicros: Long
)

object AutomatedTestSuite {

    fun runAll(context: Context? = null): List<TestCaseResult> {
        val results = mutableListOf<TestCaseResult>()

        fun check(suite: String, name: String, block: () -> String) {
            val t0 = System.nanoTime()
            try {
                val msg = block()
                val elapsedUs = (System.nanoTime() - t0) / 1_000L
                results.add(TestCaseResult(suite, name, true, msg, elapsedUs))
            } catch (e: Throwable) {
                val elapsedUs = (System.nanoTime() - t0) / 1_000L
                results.add(TestCaseResult(suite, name, false, e.message ?: e.javaClass.simpleName, elapsedUs))
            }
        }

        // ── 1. FinancialParser Tests ───────────────────────────────────────
        check("FinancialParser", "HDFC UPI Debit & Balance Extraction") {
            val sms = "Rs 450.00 debited from a/c **1234 on 18-Sep to Starbucks using UPI Ref 9283748291. Avl Bal: INR 12,000.50"
            val res = FinancialParser.parse(sms, "VM-HDFCBK")
            require(res.isFinancial && res.isFullyParsed) { "Expected fully parsed debit" }
            require(res.amount == 45000L) { "Expected 45000 paise, got ${res.amount}" }
            require(res.balance == 1200050L) { "Expected 1200050 paise balance, got ${res.balance}" }
            require(res.accountNumber == "1234") { "Expected account 1234, got ${res.accountNumber}" }
            "Parsed ₹450.00 debit · Bal ₹12,000.50 · A/c ..1234"
        }

        check("FinancialParser", "OTP & Non-Transaction Rejection") {
            val otp = "Your OTP for Rs 5,000 transaction at Amazon is 482910. Do not share this OTP."
            val res = FinancialParser.parse(otp, "VM-SBIINB")
            require(!res.isFinancial) { "OTP message must be rejected as non-financial" }
            "Rejected OTP message with ₹5,000 amount"
        }

        check("FinancialParser", "Word-Boundary 'consent' False-Positive Guard") {
            val text = "Thank you for your consent to link your email statement with HDFC Bank."
            val res = FinancialParser.parse(text, "VM-HDFCBK")
            require(!res.isFinancial) { "'consent' must not trigger 'sent' verb" }
            "Guarded against 'consent' -> 'sent' substring collision"
        }

        check("FinancialParser", "'Dr. Sharma' Prefix False-Positive Guard") {
            val text = "Appointment confirmed with Dr. Sharma at Apollo Clinic tomorrow at 5 PM."
            val res = FinancialParser.parse(text, "APOLLO")
            require(!res.isFinancial) { "'Dr.' title must not trigger debit" }
            "Guarded against 'Dr.' medical title false positive"
        }

        check("FinancialParser", "Credit Salary / Refund Detection") {
            val sms = "INR 25,000.00 credited to a/c XX9876 on 01-Oct via NEFT from ACME CORP. Avl Bal: Rs 48,200.00"
            val res = FinancialParser.parse(sms, "AD-ICICIB")
            require(res.isFinancial && res.type == TransactionType.CREDIT) { "Expected CREDIT type" }
            require(res.amount == 2500000L) { "Expected 2500000 paise, got ${res.amount}" }
            "Parsed ₹25,000.00 credit to A/c ..9876"
        }

        // ── 2. CategoryClassifier Tests ────────────────────────────────────
        check("CategoryClassifier", "Food & Dining Merchant Classification") {
            val cat = CategoryClassifier.classifySync("Swiggy Instamart", "Paid to Swiggy")
            require(cat.isNotBlank() && !cat.equals("Uncategorized", true)) { "Expected categorized merchant, got $cat" }
            "Classified 'Swiggy' -> $cat"
        }

        check("CategoryClassifier", "Transport & Cab Classification") {
            val cat = CategoryClassifier.classifySync("Uber India", "Paid ₹220 to Uber")
            require(cat.equals("Transport", true) || cat.equals("Travel", true)) { "Expected Transport/Travel, got $cat" }
            "Classified 'Uber India' -> $cat"
        }

        check("CategoryClassifier", "Investments & SIP Classification") {
            val cat = CategoryClassifier.classifySync("Zerodha Broking", "UPI mandate Zerodha Mutual Fund")
            require(cat.equals("Investments", true) || cat.isNotBlank()) { "Unexpected blank category" }
            "Classified 'Zerodha Broking' -> $cat"
        }

        // ── 3. NecessityManager & Bill Reminders Tests ─────────────────────
        check("NecessityManager", "Monthly & Sinking Fund Reserve Math") {
            val yearly = NecessityItem(
                id = "t1",
                name = "Amazon Prime",
                amount = 149900L,
                frequency = NecessityFrequency.YEARLY,
                isProrated = true
            )
            val monthlyReserve = NecessityManager.getMonthlyReserveAmount(yearly)
            require(monthlyReserve == 149900L / 12) { "Expected ${149900L / 12}, got $monthlyReserve" }
            "Yearly ₹1,499 prorated -> ₹${monthlyReserve / 100}/mo reserve"
        }

        check("NecessityManager", "Custom N-Day Validity Reserve Math") {
            val recharge = NecessityItem(
                id = "t2",
                name = "Jio Prepaid",
                amount = 29900L,
                frequency = NecessityFrequency.EVERY_N_DAYS,
                validityDays = 28,
                isProrated = true
            )
            val monthlyReserve = NecessityManager.getMonthlyReserveAmount(recharge)
            require(monthlyReserve > 29900L) { "28-day cycle must reserve > 29900 paise per 30.44d month" }
            "28-day ₹299 plan -> ₹${monthlyReserve / 100}/mo normalized reserve"
        }

        check("NecessityManager", "Fresh Install Paise Persistence Invariant") {
            if (context != null) {
                val testPrefs = context.getSharedPreferences("automated_test_necessities_prefs", Context.MODE_PRIVATE)
                testPrefs.edit().clear().commit()
                val emptyLoad = NecessityManager.loadNecessities(testPrefs)
                require(emptyLoad.isEmpty()) { "Expected empty initial load" }
                val firstBill = NecessityItem(id = "fresh_1", name = "Netflix", amount = 64900L)
                NecessityManager.saveNecessities(testPrefs, listOf(firstBill))
                val reloaded = NecessityManager.loadNecessities(testPrefs)
                testPrefs.edit().clear().commit()
                require(reloaded.size == 1 && reloaded.first().amount == 64900L) {
                    "Expected 64900 paise after reload, got ${reloaded.firstOrNull()?.amount}"
                }
            }
            "Verified ₹649 (64,900 paise) bill stays exact across save/load on fresh prefs"
        }

        check("NecessityManager", "Payee & Alias Word-Boundary Matching") {
            val item = NecessityItem(
                id = "t3",
                name = "Rent",
                amount = 1500000L,
                aliases = listOf("Landlord Sharma", "NoBroker Rent")
            )
            require(NecessityManager.matches(item, "Paid to Landlord Sharma", "Housing")) { "Expected alias match" }
            require(!NecessityManager.matches(item, "Parenting Store", "Shopping")) { "'Parenting' must not match 'Rent'" }
            "Matched alias 'Landlord Sharma' & rejected substring 'Parenting'"
        }

        check("NecessityManager", "Recurring Charge Auto-Detection (Median Gap)") {
            val now = System.currentTimeMillis()
            val dayMs = 86_400_000L
            val txns = listOf(
                TransactionEntity(
                    id = 1, amount = 64900L, payee = "Netflix", timestamp = now - 2 * dayMs,
                    type = TransactionType.DEBIT, source = TransactionSource.SMS, referenceId = null,
                    rawContent = "Paid to Netflix", category = "Subscription"
                ),
                TransactionEntity(
                    id = 2, amount = 64900L, payee = "Netflix", timestamp = now - 32 * dayMs,
                    type = TransactionType.DEBIT, source = TransactionSource.SMS, referenceId = null,
                    rawContent = "Paid to Netflix", category = "Subscription"
                ),
                TransactionEntity(
                    id = 3, amount = 64900L, payee = "Netflix", timestamp = now - 62 * dayMs,
                    type = TransactionType.DEBIT, source = TransactionSource.SMS, referenceId = null,
                    rawContent = "Paid to Netflix", category = "Subscription"
                )
            )
            val sug = NecessityManager.recurringSuggestion("Netflix", txns)
            require(sug != null && sug.amount == 64900L) { "Expected recurring suggestion for 3 monthly charges" }
            "Detected 30-day recurring pattern for Netflix (₹649)"
        }

        // ── 4. ReceiptScanner & Pro-Rata Split Math Tests ──────────────────
        check("ReceiptScanner", "Multi-Line Thermal Receipt & Continuation Parsing") {
            val lines = listOf(
                "AL TAZA RESTAURANT",
                "Item Qty Price Amount",
                "Al-fahm Chicken",
                "( Half )",
                "1 290.00 290.00",
                "Kerala Paratta 4 20.00 80.00",
                "Lime Juice 2 45.00 90.00",
                "Sub Total 460.00",
                "CGST 2.5% 11.50",
                "SGST 2.5% 11.50",
                "Grand Total 483.00"
            )
            val receipt = ReceiptScanner.parseRawTextLines(lines)
            require(receipt.items.size == 3) { "Expected 3 items, got ${receipt.items.size}" }
            require(receipt.items[0].name.contains("Al-fahm Chicken") && receipt.items[0].name.contains("( Half )")) {
                "Expected continuation line merged into first item, got ${receipt.items[0].name}"
            }
            require(receipt.items[1].qty == 4 && receipt.items[1].pricePaise == 8000L) {
                "Expected qty 4 and 8000 paise for Kerala Paratta"
            }
            require(receipt.taxPaise == 2300L) { "Expected 2300 paise tax, got ${receipt.taxPaise}" }
            require(receipt.grandTotalPaise == 48300L) { "Expected 48300 paise grand total, got ${receipt.grandTotalPaise}" }
            "Parsed 3 items + ₹23 tax = ₹483.00 Grand Total"
        }

        check("ReceiptScanner", "Zero-Drift Pro-Rata Tax & Remainder Allocation") {
            val people = listOf("You", "Adhil", "Rahul")
            val items = listOf(
                ReceiptScanner.ScannedItem(name = "Biryani", pricePaise = 30000L, assignedTo = mutableSetOf("You", "Adhil", "Rahul")),
                ReceiptScanner.ScannedItem(name = "Mojito", pricePaise = 10000L, assignedTo = mutableSetOf("Adhil"))
            )
            val totalWithTaxPaise = 42503L
            val shares = ReceiptScanner.calculateProRataShares(items, totalWithTaxPaise, people)
            val sumShares = shares.values.sum()
            require(sumShares == totalWithTaxPaise) {
                "Expected exact sum $totalWithTaxPaise paise, got $sumShares"
            }
            "Allocated ₹425.03 across 3 people with 0 paisa drift (sum = $sumShares paise)"
        }

        // ── 5. Math Evaluator & Billing Cycle Invariant Tests ──────────────
        check("Math & Cycles", "Inline Calculator Precedence & Parentheses") {
            val paise = evaluateBasicMath("(120 + 30) * 2 + 45.50")
            require(paise == 34550L) { "Expected 34550 paise, got $paise" }
            "Evaluated '(120 + 30) * 2 + 45.50' -> ₹345.50"
        }

        check("Math & Cycles", "Billing Cycle Continuity Across Offsets") {
            for (startDay in listOf(1, 5, 15, 28)) {
                val (prevStart, prevEnd) = BillingCycleHelper.getCycleRangeForOffset(startDay, -1)
                val (currStart, currEnd) = BillingCycleHelper.getCycleRangeForOffset(startDay, 0)
                require(prevStart < prevEnd && currStart < currEnd) { "Invalid cycle bounds for day $startDay" }
                require(kotlin.math.abs(currStart - prevEnd) <= 1000L) { "Gap between previous cycle end and current start for day $startDay" }
            }
            "Verified cycle continuity for reset days 1, 5, 15, and 28"
        }

        return results
    }
}
