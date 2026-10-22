package com.expensetracker.offline.util

import java.util.Locale

object AccountNormalizer {
    fun bankName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        val withoutCc = if (cleaned.endsWith(" CC", ignoreCase = true) &&
            !cleaned.equals("OneCard CC", ignoreCase = true) &&
            !cleaned.equals("American Express CC", ignoreCase = true)
        ) {
            cleaned.substring(0, cleaned.length - 3).trim()
        } else {
            cleaned
        }
        return withoutCc.uppercase(Locale.ROOT)
    }

    fun accountNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digitsOnly = raw.trim().trimStart('X', 'x', '*').filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return null
        return if (digitsOnly.length > 4) digitsOnly.takeLast(4) else digitsOnly
    }
}
