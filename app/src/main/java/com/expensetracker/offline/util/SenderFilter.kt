package com.expensetracker.offline.util

/**
 * Cheap sender classification for SMS so personal chats and promotions never become transactions.
 *  - Indian DLT headers look like "VM-HDFCBK-S" / "AX-SBIINB-T" (the last part is the category:
 *    -S service, -T transactional, -G government, -P PROMOTIONAL).
 *  - Plain phone numbers are people ("sent you Rs 500" from a friend), not banks.
 */
object SenderFilter {
    private val PHONE_NUMBER = Regex("""^\+?[\d\s\-()]{7,}$""")
    private val PROMOTIONAL_SUFFIX = Regex("""(?i)-P$""")

    fun isAllowedSmsSender(sender: String?): Boolean {
        val s = sender?.trim().orEmpty()
        if (s.isEmpty()) return false
        if (PHONE_NUMBER.matches(s)) return false
        if (PROMOTIONAL_SUFFIX.containsMatchIn(s)) return false
        return true
    }
}
