package com.expensetracker.offline.data.repository

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persists [ManualAccount]s as one small JSON list in the app's private preferences. */
class ManualAccountStore(private val prefs: SharedPreferences) {

    fun load(): List<ManualAccount> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { ManualAccountKind.valueOf(o.optString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                ManualAccount(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    kind = kind,
                    name = o.optString("name"),
                    amountPaise = o.optLong("amount", 0L).coerceAtLeast(0L),
                    monthlyPaise = o.optLong("monthly", 0L).coerceAtLeast(0L),
                    dueDay = o.optInt("dueDay", 0).coerceIn(0, 28),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(account: ManualAccount) {
        val all = load().filter { it.id != account.id } + account
        save(all)
    }

    fun delete(id: String) = save(load().filter { it.id != id })

    private fun save(items: List<ManualAccount>) {
        val arr = JSONArray()
        items.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("kind", a.kind.name)
                    .put("name", a.name)
                    .put("amount", a.amountPaise)
                    .put("monthly", a.monthlyPaise)
                    .put("dueDay", a.dueDay)
                    .put("updatedAt", a.updatedAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "key_manual_accounts_json"
    }
}
