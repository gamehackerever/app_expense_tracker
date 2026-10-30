package com.expensetracker.offline.engine.parser

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * International card alerts ("USD 2.12 spent on Kotak Credit Card ... Avl limit INR 38898.03") state the
 * amount in the foreign currency only. The app cannot know the bank's exact rate, so it books an estimate and
 * refines it to the exact INR figure when the card's previous available limit is known (see
 * TransactionRepository.resolveForeignAmount). Rates are deliberately round, approximate numbers.
 */
object ForeignCurrency {
    /** Approximate INR per 1 unit. */
    private val APPROX_INR_PER_UNIT: Map<String, String> = mapOf(
        "USD" to "88", "EUR" to "102", "GBP" to "117", "AED" to "24", "SGD" to "68", "CAD" to "63",
        "AUD" to "58", "JPY" to "0.58", "CHF" to "110", "SAR" to "23.5", "THB" to "2.7", "MYR" to "21",
        "HKD" to "11.3", "NZD" to "51", "CNY" to "12.4", "ZAR" to "5", "QAR" to "24", "KWD" to "287",
        "BHD" to "233", "OMR" to "229", "SEK" to "9.3", "NOK" to "8.7", "DKK" to "13.7", "IDR" to "0.0055",
        "PHP" to "1.5", "KRW" to "0.063", "LKR" to "0.29", "NPR" to "0.62"
    )

    val codes: Set<String> get() = APPROX_INR_PER_UNIT.keys

    /** @param amount the foreign amount as written, e.g. "2.12" or "1,250.00". @return paise, or null if unknown. */
    fun approxInrPaise(code: String, amount: String): Long? {
        val rate = APPROX_INR_PER_UNIT[code.uppercase()]?.let { BigDecimal(it) } ?: return null
        val value = amount.replace(",", "").toBigDecimalOrNull() ?: return null
        val paise = value.multiply(rate).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
        return paise.takeIf { it > 0L }
    }
}
