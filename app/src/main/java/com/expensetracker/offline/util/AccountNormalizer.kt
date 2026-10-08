package com.expensetracker.offline.util

import java.util.Locale

object AccountNormalizer {
    fun bankName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.ROOT)
    }

    fun accountNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().trimStart('X', 'x', '*').ifBlank { null }
    }
}