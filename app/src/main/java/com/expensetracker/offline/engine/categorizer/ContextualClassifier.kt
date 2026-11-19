package com.expensetracker.offline.engine.categorizer

import java.util.Calendar
import java.util.Locale

/**
 * Evaluates contextual and behavioral signals (amount, time of day, day of week,
 * month periodicity, and standard Indian pricing tiers) to provide probabilistic categorization.
 */
object ContextualClassifier {

    data class ContextualDecision(
        val category: String,
        val confidence: Float,
        val explanation: String
    )

    // Standard Indian Telecom prepaid/postpaid plan amounts (Jio, Airtel, Vi, BSNL) in Rupees
    private val POPULAR_TELCO_PLANS_RUPEES = setOf(
        19L, 29L, 65L, 149L, 155L, 179L, 199L, 209L, 239L, 249L, 269L, 299L,
        319L, 349L, 359L, 399L, 449L, 479L, 539L, 666L, 699L, 719L, 749L, 779L,
        839L, 859L, 999L, 1029L, 1199L, 1499L, 2999L, 3359L, 3599L
    )

    private val TELCO_KEYWORDS = listOf(
        "jio", "airtel", "vodafone", "vi ", "bsnl", "recharge", "prepaid",
        "postpaid", "dth", "tataplay", "broadband", "fiber"
    )

    private val FOOD_DINING_TERMS = listOf(
        "hotel", "restaurant", "cafe", "dhaba", "bhojanalaya", "canteen", "mess",
        "bakers", "bakery", "snack", "sweets", "juice", "chai", "tea", "coffee",
        "breakfast", "lunch", "dinner", "tiffin", "meals", "biryani", "dosa", "puff"
    )

    private val STATIONERY_TERMS = listOf(
        "pen", "pens", "pencil", "pencils", "xerox", "photostat", "print",
        "paper", "notes", "notebook", "stationery", "stationary"
    )

    private val RENT_HOUSING_TERMS = listOf(
        "rent", "maintenance", "society", "flat", "pg", "hostel", "room rent", "house rent"
    )

    private val ENTERTAINMENT_TERMS = listOf(
        "movie", "cinema", "theatre", "tickets", "show", "multiplex", "imax", "pvr", "inox"
    )

    /**
     * Evaluates contextual spending signals given amount, timestamp, payee, note, and raw narration.
     */
    fun evaluate(
        amountPaise: Long?,
        timestamp: Long?,
        payee: String,
        note: String?,
        rawContent: String
    ): ContextualDecision? {
        if (amountPaise == null || amountPaise <= 0L) return null

        val amountRupees = amountPaise / 100L
        val combinedText = "${payee.lowercase(Locale.ROOT)} ${note?.lowercase(Locale.ROOT) ?: ""} ${rawContent.lowercase(Locale.ROOT)}"

        // 1. Telecom exact plan matching (₹299, ₹666, ₹719, etc.)
        if (POPULAR_TELCO_PLANS_RUPEES.contains(amountRupees)) {
            val hasTelcoHint = TELCO_KEYWORDS.any { combinedText.contains(it) }
            if (hasTelcoHint) {
                return ContextualDecision(
                    category = "Recharge & Telecom",
                    confidence = 0.90f,
                    explanation = "Exact Indian telco recharge tier ₹$amountRupees with telecom signal"
                )
            }
        }

        if (timestamp == null || timestamp <= 0L) return null

        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)

        // 2. Month-start Rental & Society Maintenance Window (1st to 7th of month)
        if (dayOfMonth in 1..7 && amountRupees in 4000L..80000L) {
            val hasRentHint = RENT_HOUSING_TERMS.any { combinedText.contains(it) }
            if (hasRentHint) {
                return ContextualDecision(
                    category = "Rent & Housing",
                    confidence = 0.85f,
                    explanation = "Month-start rental/maintenance window (day $dayOfMonth, ₹$amountRupees)"
                )
            }
        }

        // 3. Daytime Micro-Amounts for Stationery / Xerox (₹5 - ₹60, 8 AM - 6 PM)
        if (hour in 8..18 && amountRupees in 5L..60L) {
            val hasStationeryHint = STATIONERY_TERMS.any { combinedText.contains(it) }
            if (hasStationeryHint) {
                return ContextualDecision(
                    category = "Education",
                    confidence = 0.85f,
                    explanation = "Daytime micro-expense for stationery/supplies (₹$amountRupees)"
                )
            }
        }

        // 4. Time-of-Day Meal & Dining Windows
        val hasFoodHint = FOOD_DINING_TERMS.any { combinedText.contains(it) }
        if (hasFoodHint) {
            when (hour) {
                // Morning Breakfast & Chai (6:00 AM - 10:30 AM)
                in 6..10 -> {
                    if (amountRupees in 10L..300L) {
                        return ContextualDecision(
                            category = "Food & Dining",
                            confidence = 0.85f,
                            explanation = "Morning breakfast/chai window ($hour:00, ₹$amountRupees)"
                        )
                    }
                }
                // Afternoon Lunch (12:00 PM - 3:30 PM)
                in 12..15 -> {
                    if (amountRupees in 40L..800L) {
                        return ContextualDecision(
                            category = "Food & Dining",
                            confidence = 0.85f,
                            explanation = "Lunch dining window ($hour:00, ₹$amountRupees)"
                        )
                    }
                }
                // Evening Tea & Snacks (4:30 PM - 7:00 PM)
                in 16..19 -> {
                    if (amountRupees in 10L..300L) {
                        return ContextualDecision(
                            category = "Food & Dining",
                            confidence = 0.85f,
                            explanation = "Evening tea & snack window ($hour:00, ₹$amountRupees)"
                        )
                    }
                }
                // Dinner (7:30 PM - 11:30 PM)
                in 20..23 -> {
                    if (amountRupees in 80L..2500L) {
                        return ContextualDecision(
                            category = "Food & Dining",
                            confidence = 0.85f,
                            explanation = "Evening dinner window ($hour:00, ₹$amountRupees)"
                        )
                    }
                }
            }
        }

        // 5. Weekend Leisure & Entertainment Windows (Friday evening, Saturday, Sunday)
        val isWeekendOrFridayEve = (dayOfWeek == Calendar.FRIDAY && hour >= 17) ||
                dayOfWeek == Calendar.SATURDAY ||
                dayOfWeek == Calendar.SUNDAY

        if (isWeekendOrFridayEve && amountRupees in 200L..3000L) {
            val hasEntertainmentHint = ENTERTAINMENT_TERMS.any { combinedText.contains(it) }
            if (hasEntertainmentHint) {
                return ContextualDecision(
                    category = "Entertainment",
                    confidence = 0.85f,
                    explanation = "Weekend entertainment/leisure window (day $dayOfWeek, ₹$amountRupees)"
                )
            }
        }

        return null
    }
}
