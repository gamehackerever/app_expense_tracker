package com.expensetracker.offline.data.repository

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("expense_tracker_prefs", Context.MODE_PRIVATE)

    var lowBalanceAlertEnabled: Boolean
        get() = prefs.getBoolean("key_low_balance_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("key_low_balance_alert_enabled", value).apply()

    var lowBalanceThreshold: Float
        get() = prefs.getFloat("key_low_balance_threshold", 1000f)
        set(value) = prefs.edit().putFloat("key_low_balance_threshold", value).apply()

    var monthlyBudget: Long
        get() {
            val raw = try {
                prefs.getString("key_monthly_budget_v2", null)
            } catch (_: ClassCastException) {
                try {
                    prefs.getLong("key_monthly_budget_v2", -1L).takeIf { it > 0 }?.toString()
                } catch (_: ClassCastException) {
                    try {
                        prefs.getFloat("key_monthly_budget_v2", -1f).takeIf { it > 0 }?.toLong()?.toString()
                    } catch (_: ClassCastException) {
                        null
                    }
                }
            }
            if (raw != null) {
                val parsed = raw.replace("L", "", ignoreCase = true).trim().toLongOrNull()
                if (parsed != null && parsed > 0) return parsed
            }

            val legacyValue = try {
                prefs.getFloat("key_monthly_budget", 15000f).toLong()
            } catch (_: ClassCastException) {
                try {
                    prefs.getLong("key_monthly_budget", 15000L)
                } catch (_: ClassCastException) {
                    15000L
                }
            }
            prefs.edit().putString("key_monthly_budget_v2", legacyValue.toString()).apply()
            if (!prefs.contains("key_budget_explicitly_set")) {
                isBudgetExplicitlySet = (legacyValue != 15000L)
            }
            return legacyValue
        }
        set(value) {
            prefs.edit().putString("key_monthly_budget_v2", value.toString()).apply()
            isBudgetExplicitlySet = true
        }

    var isBudgetExplicitlySet: Boolean
        get() = prefs.getBoolean("key_budget_explicitly_set", false)
        private set(value) = prefs.edit().putBoolean("key_budget_explicitly_set", value).apply()

    var startDayOfMonth: Int
        get() = prefs.getInt("key_start_day_of_month", 1)
        set(value) = prefs.edit().putInt("key_start_day_of_month", value).apply()

    var skipReviewQueue: Boolean
        get() = prefs.getBoolean("key_skip_review_queue", false)
        set(value) = prefs.edit().putBoolean("key_skip_review_queue", value).apply()

    var fallbackPayee: String
        get() = prefs.getString("key_fallback_payee", "UPI Payment") ?: "UPI Payment"
        set(value) = prefs.edit().putString("key_fallback_payee", value).apply()

    var autoSaveUnknownDeductions: Boolean
        get() = prefs.getBoolean("key_autosave_unknown_deductions", false)
        set(value) = prefs.edit().putBoolean("key_autosave_unknown_deductions", value).apply()

    var skipScanReview: Boolean
        get() = prefs.getBoolean("key_skip_scan_review", false)
        set(value) = prefs.edit().putBoolean("key_skip_scan_review", value).apply()

    var scanFallbackPayee: String
        get() = prefs.getString("key_scan_fallback_payee", "Bank Transfer") ?: "Bank Transfer"
        set(value) = prefs.edit().putString("key_scan_fallback_payee", value).apply()

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean("key_auto_backup_enabled", false)
        set(value) = prefs.edit().putBoolean("key_auto_backup_enabled", value).apply()

    var backupFrequency: String
        get() = prefs.getString("key_backup_frequency", "Weekly") ?: "Weekly"
        set(value) = prefs.edit().putString("key_backup_frequency", value).apply()

    var backupFolderUri: String
        get() = prefs.getString("key_backup_folder_uri", "") ?: ""
        set(value) = prefs.edit().putString("key_backup_folder_uri", value).apply()

    var appLockEnabled: Boolean
        get() = prefs.getBoolean("key_app_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("key_app_lock_enabled", value).apply()

    var appThemeMode: String
        get() = prefs.getString("key_app_theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("key_app_theme_mode", value).apply()

    var myUpiVpa: String
        get() = prefs.getString("key_my_upi_vpa", "") ?: ""
        set(value) = prefs.edit().putString("key_my_upi_vpa", value).apply()

    // FIXED: Persist scan batches so the Undo button survives rotation (P1)
    var lastImportedTxnIds: List<Long>
        get() = prefs.getString("key_last_scan_txns", "")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(value) = prefs.edit().putString("key_last_scan_txns", value.joinToString(",")).apply()

    var lastImportedReviewIds: List<Long>
        get() = prefs.getString("key_last_scan_reviews", "")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(value) = prefs.edit().putString("key_last_scan_reviews", value.joinToString(",")).apply()

    fun clearLastScanBatch() {
        prefs.edit().remove("key_last_scan_txns").remove("key_last_scan_reviews").apply()
    }
}

private val UPI_VPA_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63}$")

/** True for a well-formed UPI VPA such as `name@okaxis` or `98765.43210@ybl`. */
fun isValidUpiVpa(vpa: String): Boolean = UPI_VPA_REGEX.matches(vpa.trim())