package com.expensetracker.offline.util

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Standardized Haptic Feedback helpers for Expense Tracker to ensure consistent tactile UI response.
 */
fun HapticFeedback.tick() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

fun HapticFeedback.click() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

fun HapticFeedback.confirm() {
    performHapticFeedback(HapticFeedbackType.LongPress)
}

fun HapticFeedback.delete() {
    performHapticFeedback(HapticFeedbackType.LongPress)
}
