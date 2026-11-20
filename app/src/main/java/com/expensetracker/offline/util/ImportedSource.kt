package com.expensetracker.offline.util

import java.util.Calendar

/**
 * Rows added by "Import payment statement" carry `rawContent = "Imported from <format label>"`.
 * Older imports also wrote the same text into the note. These helpers let the UI show a quiet source tag
 * ("PhonePe") instead of repeating that sentence on every row, without any database change.
 */
object ImportedSource {
    private const val PREFIX = "Imported from "

    fun isImported(rawContent: String?): Boolean {
        val trimmed = rawContent?.trimStart() ?: return false
        return trimmed.startsWith(PREFIX, ignoreCase = true) ||
               trimmed.startsWith("Imported statement", ignoreCase = true) ||
               trimmed.startsWith("Imported CSV", ignoreCase = true) ||
               trimmed.startsWith("Imported PDF", ignoreCase = true)
    }

    /** Short tag for the row, or null when the transaction was not imported from a statement. */
    fun shortLabel(rawContent: String?): String? {
        if (!isImported(rawContent)) return null
        val trimmed = rawContent!!.trimStart()
        val body = if (trimmed.startsWith(PREFIX, ignoreCase = true)) {
            trimmed.substring(PREFIX.length).lowercase()
        } else {
            trimmed.lowercase()
        }
        return when {
            body.contains("phonepe") -> "PhonePe"
            body.contains("google") || body.contains("gpay") -> "Google Pay"
            body.contains("paytm") -> "Paytm"
            body.contains("csv") -> "CSV"
            body.contains("pdf") || body.contains("statement") || body.contains("bank") -> "Statement"
            else -> "Statement"
        }
    }

    /** True for the auto-written "Imported from …" note, which should never be shown as a user note. */
    fun isSystemNote(note: String?): Boolean = isImported(note)

    /** Statements with only a date are stored at local midnight; showing "00:00" for them is misleading. */
    fun hasNoRealTime(timestamp: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = timestamp }
        return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0 &&
            c.get(Calendar.SECOND) == 0 && c.get(Calendar.MILLISECOND) == 0
    }
}
