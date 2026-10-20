package com.expensetracker.offline.data.repository

import android.content.Context
import android.content.SharedPreferences

data class CreditCardConfig(
    val accountKey: String,
    val isCreditCard: Boolean = true,
    val creditLimitPaise: Long = 10_000_000L, // Default ₹1,00,000
    val statementDay: Int = 15,
    val dueDay: Int = 5
)

class SettingsRepository(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lowBalanceAlertEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOW_BALANCE_ALERT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOW_BALANCE_ALERT_ENABLED, value).apply()

    var lowBalanceThreshold: Float
        get() = try {
            prefs.getFloat(KEY_LOW_BALANCE_THRESHOLD, 1000f)
        } catch (_: ClassCastException) {
            try {
                prefs.getLong(KEY_LOW_BALANCE_THRESHOLD, 1000L).toFloat()
            } catch (_: ClassCastException) {
                1000f
            }
        }
        set(value) = prefs.edit().putFloat(KEY_LOW_BALANCE_THRESHOLD, value).apply()

    var monthlyBudget: Long
        get() {
            val raw = try {
                prefs.getString(KEY_MONTHLY_BUDGET_V2, null)
            } catch (_: ClassCastException) {
                try {
                    prefs.getLong(KEY_MONTHLY_BUDGET_V2, -1L).takeIf { it > 0 }?.toString()
                } catch (_: ClassCastException) {
                    try {
                        prefs.getFloat(KEY_MONTHLY_BUDGET_V2, -1f).takeIf { it > 0 }?.toLong()?.toString()
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
                prefs.getFloat(KEY_MONTHLY_BUDGET_LEGACY, 15000f).toLong()
            } catch (_: ClassCastException) {
                try {
                    prefs.getLong(KEY_MONTHLY_BUDGET_LEGACY, 15000L)
                } catch (_: ClassCastException) {
                    15000L
                }
            }
            prefs.edit().putString(KEY_MONTHLY_BUDGET_V2, legacyValue.toString()).apply()
            if (!prefs.contains(KEY_BUDGET_EXPLICITLY_SET)) {
                isBudgetExplicitlySet = (legacyValue != 15000L)
            }
            return legacyValue
        }
        set(value) {
            prefs.edit().putString(KEY_MONTHLY_BUDGET_V2, value.toString()).apply()
            isBudgetExplicitlySet = true
        }

    var isBudgetExplicitlySet: Boolean
        get() = prefs.getBoolean(KEY_BUDGET_EXPLICITLY_SET, false)
        private set(value) = prefs.edit().putBoolean(KEY_BUDGET_EXPLICITLY_SET, value).apply()

    var startDayOfMonth: Int
        get() = prefs.getInt(KEY_START_DAY_OF_MONTH, 1)
        set(value) = prefs.edit().putInt(KEY_START_DAY_OF_MONTH, value).apply()

    var necessityChunkLimit: Long
        get() {
            if (!prefs.contains(KEY_NECESSITY_CHUNK)) return 0L
            return try {
                prefs.getLong(KEY_NECESSITY_CHUNK, 0L)
            } catch (_: ClassCastException) {
                try {
                    val legacyFloat = prefs.getFloat(KEY_NECESSITY_CHUNK, 0f).toLong()
                    prefs.edit().putLong(KEY_NECESSITY_CHUNK, legacyFloat).apply()
                    legacyFloat
                } catch (_: ClassCastException) {
                    0L
                }
            }
        }
        set(value) = prefs.edit().putLong(KEY_NECESSITY_CHUNK, value).apply()

    var skipReviewQueue: Boolean
        get() = prefs.getBoolean(KEY_SKIP_REVIEW_QUEUE, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_REVIEW_QUEUE, value).apply()

    var fallbackPayee: String
        get() = prefs.getString(KEY_FALLBACK_PAYEE, "UPI Payment")?.ifBlank { "UPI Payment" } ?: "UPI Payment"
        set(value) = prefs.edit().putString(KEY_FALLBACK_PAYEE, value).apply()

    var autoSaveUnknownDeductions: Boolean
        get() = prefs.getBoolean(KEY_AUTOSAVE_UNKNOWN_DEDUCTIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSAVE_UNKNOWN_DEDUCTIONS, value).apply()

    var skipScanReview: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SCAN_REVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_SCAN_REVIEW, value).apply()

    var scanFallbackPayee: String
        get() = prefs.getString(KEY_SCAN_FALLBACK_PAYEE, null)?.ifBlank { null }
            ?: prefs.getString(KEY_FALLBACK_PAYEE, "Bank Transfer")?.ifBlank { "Bank Transfer" }
            ?: "Bank Transfer"
        set(value) = prefs.edit().putString(KEY_SCAN_FALLBACK_PAYEE, value).apply()

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, value).apply()

    var backupFrequency: String
        get() = prefs.getString(KEY_BACKUP_FREQUENCY, "Weekly") ?: "Weekly"
        set(value) = prefs.edit().putString(KEY_BACKUP_FREQUENCY, value).apply()

    var backupFolderUri: String
        get() = prefs.getString(KEY_BACKUP_FOLDER_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKUP_FOLDER_URI, value).apply()

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    var hideWidgetAmounts: Boolean
        get() = prefs.getBoolean(KEY_HIDE_WIDGET_AMOUNTS, appLockEnabled)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_WIDGET_AMOUNTS, value).apply()

    var dailyDigestEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_DIGEST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_DIGEST_ENABLED, value).apply()

    var billRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_BILL_REMINDERS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BILL_REMINDERS_ENABLED, value).apply()

    var disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, value).apply()

    var appThemeMode: String
        get() = prefs.getString(KEY_APP_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_APP_THEME_MODE, value).apply()

    var myUpiVpa: String
        get() = prefs.getString(KEY_MY_UPI_VPA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MY_UPI_VPA, value).apply()

    var customCategories: Set<String>
        get() = prefs.getStringSet(KEY_CUSTOM_CATEGORIES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_CUSTOM_CATEGORIES, value).apply()

    fun getMerchantAlias(payee: String): String? =
        prefs.getString("$MERCHANT_ALIAS_PREFIX${payee.trim().lowercase()}", null)

    fun setMerchantAlias(oldPayee: String, newPayee: String) {
        prefs.edit().putString("$MERCHANT_ALIAS_PREFIX${oldPayee.trim().lowercase()}", newPayee.trim()).apply()
    }

    var lastImportedTxnIds: List<Long>
        get() = prefs.getString(KEY_LAST_SCAN_TXNS, "")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_LAST_SCAN_TXNS, value.joinToString(",")).apply()

    var lastImportedReviewIds: List<Long>
        get() = prefs.getString(KEY_LAST_SCAN_REVIEWS, "")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_LAST_SCAN_REVIEWS, value.joinToString(",")).apply()

    // ── Soft-Delete Crash Recovery Persistence ───────────────────────────────
    @Synchronized
    fun getPendingDeleteIds(): Set<Long> =
        prefs.getString(KEY_PENDING_DELETE_TXN_IDS, "")
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    @Synchronized
    fun addPendingDeleteIds(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val updated = getPendingDeleteIds() + ids
        prefs.edit().putString(KEY_PENDING_DELETE_TXN_IDS, updated.joinToString(",")).apply()
    }

    @Synchronized
    fun removePendingDeleteIds(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val updated = getPendingDeleteIds() - ids.toSet()
        if (updated.isEmpty()) {
            prefs.edit().remove(KEY_PENDING_DELETE_TXN_IDS).apply()
        } else {
            prefs.edit().putString(KEY_PENDING_DELETE_TXN_IDS, updated.joinToString(",")).apply()
        }
    }

    @Synchronized
    fun consumeAllPendingDeleteIds(): Set<Long> {
        val existing = getPendingDeleteIds()
        if (existing.isNotEmpty()) {
            prefs.edit().remove(KEY_PENDING_DELETE_TXN_IDS).commit()
        }
        return existing
    }

    var categoryBudgetsJson: String
        get() = prefs.getString(KEY_CATEGORY_BUDGETS_JSON, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_CATEGORY_BUDGETS_JSON, value).apply()

    fun getCategoryBudgets(): Map<String, Long> {
        val jsonStr = categoryBudgetsJson
        if (jsonStr.isBlank() || jsonStr == "{}") return emptyMap()
        return try {
            val obj = org.json.JSONObject(jsonStr)
            val result = linkedMapOf<String, Long>()
            obj.keys().forEach { key ->
                val cleanKey = key.trim()
                val limit = obj.optLong(key, 0L)
                if (cleanKey.isNotEmpty() &&
                    !cleanKey.equals("Split Settlement", ignoreCase = true) &&
                    limit > 0L
                ) {
                    val existingKey = result.keys.firstOrNull { it.equals(cleanKey, ignoreCase = true) }
                    if (existingKey != null) {
                        result[existingKey] = maxOf(result[existingKey] ?: 0L, limit)
                    } else {
                        result[cleanKey] = limit
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setCategoryBudget(category: String, limitPaise: Long) {
        val cleanCategory = category.trim()
        if (cleanCategory.isEmpty() || cleanCategory.equals("Split Settlement", ignoreCase = true)) return

        val current = getCategoryBudgets().toMutableMap()
        val matchingKey = current.keys.firstOrNull { it.equals(cleanCategory, ignoreCase = true) }

        if (limitPaise > 0L) {
            if (matchingKey != null && matchingKey != cleanCategory) {
                current.remove(matchingKey)
            }
            current[matchingKey ?: cleanCategory] = limitPaise
        } else {
            if (matchingKey != null) current.remove(matchingKey) else current.remove(cleanCategory)
        }
        saveCategoryBudgetsMap(current)
        clearDismissedCategoryWarning(cleanCategory)
    }

    private fun saveCategoryBudgetsMap(map: Map<String, Long>) {
        val obj = org.json.JSONObject()
        map.forEach { (cat, limit) ->
            if (cat.isNotBlank() && limit > 0L) {
                obj.put(cat.trim(), limit)
            }
        }
        categoryBudgetsJson = obj.toString()
    }

    fun reassignCategoryBudget(oldCategory: String, newCategory: String?) {
        val cleanOld = oldCategory.trim()
        if (cleanOld.isEmpty()) return
        val current = getCategoryBudgets().toMutableMap()
        val oldKey = current.keys.firstOrNull { it.equals(cleanOld, ignoreCase = true) } ?: return
        val oldLimit = current.remove(oldKey) ?: return

        val cleanNew = newCategory?.trim()
        if (!cleanNew.isNullOrEmpty() &&
            !cleanNew.equals("Uncategorized", ignoreCase = true) &&
            !cleanNew.equals("Split Settlement", ignoreCase = true)
        ) {
            val newKey = current.keys.firstOrNull { it.equals(cleanNew, ignoreCase = true) } ?: cleanNew
            if ((current[newKey] ?: 0L) <= 0L) {
                current[newKey] = oldLimit
            }
        }
        saveCategoryBudgetsMap(current)
        clearDismissedCategoryWarning(cleanOld)
    }

    var dismissedBudgetWarningsJson: String
        get() = prefs.getString(KEY_DISMISSED_BUDGET_WARNINGS, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_DISMISSED_BUDGET_WARNINGS, value).apply()

    fun dismissCategoryWarning(category: String, cycleStartMillis: Long, level: String = WARNING_LEVEL_OVER) {
        val cleanCategory = category.trim()
        if (cleanCategory.isEmpty()) return
        try {
            val currentLevels = getDismissedCategoryWarningLevels(cycleStartMillis).toMutableMap()
            val existingKey = currentLevels.keys.firstOrNull { it.equals(cleanCategory, ignoreCase = true) } ?: cleanCategory
            if (currentLevels[existingKey] != WARNING_LEVEL_OVER) {
                currentLevels[existingKey] = level
            }
            saveDismissedCategoryWarningLevels(cycleStartMillis, currentLevels)
        } catch (_: Exception) {}
    }

    fun clearDismissedCategoryWarning(category: String) {
        val cleanCategory = category.trim()
        if (cleanCategory.isEmpty()) return
        try {
            val raw = dismissedBudgetWarningsJson
            if (raw.isBlank() || raw == "{}") return
            val obj = org.json.JSONObject(raw)
            val savedCycle = obj.optLong("cycleStart", 0L)
            if (savedCycle <= 0L) return
            val currentLevels = getDismissedCategoryWarningLevels(savedCycle).toMutableMap()
            val keyToRemove = currentLevels.keys.firstOrNull { it.equals(cleanCategory, ignoreCase = true) } ?: return
            currentLevels.remove(keyToRemove)
            saveDismissedCategoryWarningLevels(savedCycle, currentLevels)
        } catch (_: Exception) {}
    }

    private fun saveDismissedCategoryWarningLevels(cycleStartMillis: Long, levels: Map<String, String>) {
        val levelsObj = org.json.JSONObject()
        val legacyArray = org.json.JSONArray()
        levels.forEach { (cat, lvl) ->
            levelsObj.put(cat, lvl)
            legacyArray.put(cat)
        }
        val newObj = org.json.JSONObject()
        newObj.put("cycleStart", cycleStartMillis)
        newObj.put("levels", levelsObj)
        newObj.put("categories", legacyArray)
        dismissedBudgetWarningsJson = newObj.toString()
    }

    fun getDismissedCategoryWarningLevels(cycleStartMillis: Long): Map<String, String> {
        return try {
            val raw = dismissedBudgetWarningsJson
            if (raw.isBlank() || raw == "{}") return emptyMap()
            val obj = org.json.JSONObject(raw)
            if (obj.optLong("cycleStart", 0L) != cycleStartMillis) return emptyMap()

            val result = mutableMapOf<String, String>()
            val levelsObj = obj.optJSONObject("levels")
            if (levelsObj != null) {
                levelsObj.keys().forEach { key ->
                    val lvl = levelsObj.optString(key, WARNING_LEVEL_OVER)
                    if (key.isNotBlank()) result[key.trim()] = lvl
                }
                return result
            }
            val array = obj.optJSONArray("categories") ?: return emptyMap()
            for (i in 0 until array.length()) {
                val cat = array.optString(i).trim()
                if (cat.isNotEmpty()) result[cat] = WARNING_LEVEL_NEAR
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getDismissedCategoryWarnings(cycleStartMillis: Long): Set<String> =
        getDismissedCategoryWarningLevels(cycleStartMillis).keys

    fun removeCategoryBudget(category: String) {
        setCategoryBudget(category, 0L)
    }

    var isCategoryBudgetSetupDismissed: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY_BUDGET_SETUP_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_CATEGORY_BUDGET_SETUP_DISMISSED, value).apply()

    // ── Credit Card Cycle & Limit Configurations ─────────────────────────────

    fun accountKey(bankName: String, accountNumber: String?): String =
        "${bankName.trim().lowercase()}|${accountNumber?.trim()?.lowercase() ?: "none"}"

    fun isDefaultCreditCard(bankName: String): Boolean {
        val lower = bankName.lowercase()
        return lower.endsWith(" cc") || lower.contains("credit card") ||
                lower in setOf("onecard", "slice", "american express", "amex", "lazypay", "simpl")
    }

    fun getCreditCardConfigs(): Map<String, CreditCardConfig> {
        val raw = prefs.getString(KEY_CREDIT_CARD_CONFIGS_JSON, "{}") ?: "{}"
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val map = mutableMapOf<String, CreditCardConfig>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                map[key] = CreditCardConfig(
                    accountKey = key,
                    isCreditCard = item.optBoolean("isCc", true),
                    creditLimitPaise = item.optLong("limit", 10_000_000L),
                    statementDay = item.optInt("stmtDay", 15).coerceIn(1, 28),
                    dueDay = item.optInt("dueDay", 5).coerceIn(1, 28)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getCreditCardConfig(bankName: String, accountNumber: String?): CreditCardConfig? {
        val key = accountKey(bankName, accountNumber)
        val stored = getCreditCardConfigs()[key]
        if (stored != null) return if (stored.isCreditCard) stored else null
        return if (isDefaultCreditCard(bankName)) CreditCardConfig(accountKey = key) else null
    }

    fun saveCreditCardConfig(bankName: String, accountNumber: String?, config: CreditCardConfig) {
        val key = accountKey(bankName, accountNumber)
        val current = getCreditCardConfigs().toMutableMap()
        current[key] = config.copy(accountKey = key)
        val obj = org.json.JSONObject()
        current.forEach { (k, c) ->
            obj.put(k, org.json.JSONObject().apply {
                put("isCc", c.isCreditCard)
                put("limit", c.creditLimitPaise)
                put("stmtDay", c.statementDay.coerceIn(1, 28))
                put("dueDay", c.dueDay.coerceIn(1, 28))
            })
        }
        prefs.edit().putString(KEY_CREDIT_CARD_CONFIGS_JSON, obj.toString()).apply()
    }

    fun clearLastScanBatch() {
        prefs.edit().remove(KEY_LAST_SCAN_TXNS).remove(KEY_LAST_SCAN_REVIEWS).apply()
    }

    companion object {
        const val PREFS_NAME = "expense_tracker_prefs"
        const val KEY_LOW_BALANCE_ALERT_ENABLED = "key_low_balance_alert_enabled"
        const val KEY_LOW_BALANCE_THRESHOLD = "key_low_balance_threshold"
        const val KEY_MONTHLY_BUDGET_V2 = "key_monthly_budget_v2"
        const val KEY_MONTHLY_BUDGET_LEGACY = "key_monthly_budget"
        const val KEY_BUDGET_EXPLICITLY_SET = "key_budget_explicitly_set"
        const val KEY_CATEGORY_BUDGETS_JSON = "key_category_budgets_json"
        const val KEY_CATEGORY_BUDGET_SETUP_DISMISSED = "key_category_budget_setup_dismissed"
        const val KEY_DISMISSED_BUDGET_WARNINGS = "key_dismissed_budget_warnings_v2"
        const val KEY_CREDIT_CARD_CONFIGS_JSON = "key_credit_card_configs_json"
        const val WARNING_LEVEL_NEAR = "NEAR"
        const val WARNING_LEVEL_OVER = "OVER"
        const val KEY_START_DAY_OF_MONTH = "key_start_day_of_month"
        const val KEY_NECESSITY_CHUNK = "key_necessity_chunk"
        const val KEY_SKIP_REVIEW_QUEUE = "key_skip_review_queue"
        const val KEY_FALLBACK_PAYEE = "key_fallback_payee"
        const val KEY_AUTOSAVE_UNKNOWN_DEDUCTIONS = "key_autosave_unknown_deductions"
        const val KEY_SKIP_SCAN_REVIEW = "key_skip_scan_review"
        const val KEY_SCAN_FALLBACK_PAYEE = "key_scan_fallback_payee"
        const val KEY_AUTO_BACKUP_ENABLED = "key_auto_backup_enabled"
        const val KEY_BACKUP_FREQUENCY = "key_backup_frequency"
        const val KEY_BACKUP_FOLDER_URI = "key_backup_folder_uri"
        const val KEY_APP_LOCK_ENABLED = "key_app_lock_enabled"
        const val KEY_HIDE_WIDGET_AMOUNTS = "key_hide_widget_amounts"
        const val KEY_DAILY_DIGEST_ENABLED = "key_daily_digest_enabled"
        const val KEY_BILL_REMINDERS_ENABLED = "key_bill_reminders_enabled"
        const val KEY_DISCLOSURE_ACCEPTED = "key_disclosure_accepted"
        const val KEY_APP_THEME_MODE = "key_app_theme_mode"
        const val KEY_MY_UPI_VPA = "key_my_upi_vpa"
        const val KEY_CUSTOM_CATEGORIES = "key_custom_categories"
        const val KEY_LAST_SCAN_TXNS = "key_last_scan_txns"
        const val KEY_LAST_SCAN_REVIEWS = "key_last_scan_reviews"
        const val KEY_PENDING_DELETE_TXN_IDS = "key_pending_delete_txn_ids"
        private const val MERCHANT_ALIAS_PREFIX = "merchant_alias_"
    }
}

private val UPI_VPA_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63}$")

/** True for a well-formed UPI VPA such as `name@okaxis` or `98765.43210@ybl`. */
fun isValidUpiVpa(vpa: String): Boolean = UPI_VPA_REGEX.matches(vpa.trim())