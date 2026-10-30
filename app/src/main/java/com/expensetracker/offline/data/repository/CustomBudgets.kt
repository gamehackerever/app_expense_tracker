package com.expensetracker.offline.data.repository

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A budget for one specific goal ("New laptop", "Goa trip"), separate from the monthly budget.
 * Transactions are tagged to it (TransactionEntity.budgetId); [excludeFromMonthly] keeps that spending out of the
 * monthly total, which is what you want for one-off purchases.
 */
data class CustomBudget(
    val id: String,
    val name: String,
    val targetPaise: Long,
    val excludeFromMonthly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** Persists [CustomBudget]s as one small JSON list in the app preferences (so backups carry them too). */
class CustomBudgetStore(private val prefs: SharedPreferences) {

    fun load(): List<CustomBudget> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                CustomBudget(
                    id = id,
                    name = name,
                    targetPaise = o.optLong("target", 0L).coerceAtLeast(0L),
                    excludeFromMonthly = o.optBoolean("excludeFromMonthly", true),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(budget: CustomBudget): List<CustomBudget> {
        val existing = load()
        val updated = if (existing.any { it.id == budget.id }) existing.map { if (it.id == budget.id) budget else it }
        else existing + budget
        save(updated)
        return updated
    }

    fun delete(id: String): List<CustomBudget> {
        val updated = load().filter { it.id != id }
        save(updated)
        return updated
    }

    private fun save(items: List<CustomBudget>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("target", it.targetPaise)
                    .put("excludeFromMonthly", it.excludeFromMonthly).put("createdAt", it.createdAt)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "key_custom_budgets" }
}
