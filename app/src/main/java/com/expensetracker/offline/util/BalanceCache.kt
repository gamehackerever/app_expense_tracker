package com.expensetracker.offline.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Single owner of the balance cache in SharedPreferences. All values are Long PAISE.
 *
 * Old installs may still hold Float values under the same keys (a ClassCastException on read),
 * so every read goes through [readLong], which accepts either type. Writes only ever use Long
 * and only when the new balance is NEWER than what is stored, so a stale SMS, a rescan, or an
 * old review item can never roll the baseline back.
 */
object BalanceCache {
    const val GLOBAL_KEY = "key_latest_bank_balance"
    private const val GLOBAL_TS_KEY = "key_latest_bank_balance_ts"
    private const val PREFS = "expense_tracker_prefs"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readLong(prefs: SharedPreferences, key: String): Long? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getLong(key, 0L)
        } catch (_: ClassCastException) {
            try { prefs.getFloat(key, 0f).toLong() } catch (_: ClassCastException) { null }
        }
    }

    fun timestampOf(prefs: SharedPreferences, bankName: String?, accountNumber: String?): Long =
        readLong(prefs, "${BalanceCacheKeys.key(bankName, accountNumber)}_timestamp") ?: 0L

    fun baselineOf(prefs: SharedPreferences, bankName: String?, accountNumber: String?): Long? =
        readLong(prefs, "${BalanceCacheKeys.key(bankName, accountNumber)}_official")

    /** @return true when the cache was updated (i.e. [timestamp] is newer than the stored one). */
    fun updateIfNewer(
        prefs: SharedPreferences,
        bankName: String?,
        accountNumber: String?,
        balance: Long,
        timestamp: Long
    ): Boolean {
        val key = BalanceCacheKeys.key(bankName, accountNumber)
        if (timestamp <= timestampOf(prefs, bankName, accountNumber)) return false
        val editor = prefs.edit()
            .putLong(key, balance)
            .putLong("${key}_official", balance)
            .putLong("${key}_timestamp", timestamp)
        // Global value is only a fallback for the dashboard; same newer-wins rule.
        if (timestamp > (readLong(prefs, GLOBAL_TS_KEY) ?: 0L)) {
            editor.putLong(GLOBAL_KEY, balance).putLong(GLOBAL_TS_KEY, timestamp)
        }
        editor.apply()
        return true
    }

    /** Called after "clear all" so stale baselines cannot raise false ghost alerts. */
    fun clearAll(context: Context) {
        val p = prefs(context)
        val keys = p.all.keys.filter { it.startsWith("key_latest_bank_balance") }
        if (keys.isEmpty()) return
        p.edit().apply { keys.forEach { remove(it) } }.apply()
    }
}
