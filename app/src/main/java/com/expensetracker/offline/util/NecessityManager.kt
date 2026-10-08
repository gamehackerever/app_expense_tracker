package com.expensetracker.offline.util

import android.content.SharedPreferences
import android.util.Log
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class NecessityFrequency(val label: String, val months: Int) {
    MONTHLY("Monthly", 1),
    EVERY_N_DAYS("Custom Days", 0),
    QUARTERLY("Quarterly", 3),
    HALF_YEARLY("Half-Yearly", 6),
    YEARLY("Yearly", 12)
}

data class NecessityItem(
    val id: String,
    val name: String,
    val amount: Long,
    val frequency: NecessityFrequency = NecessityFrequency.MONTHLY,
    val dueTimestamp: Long? = null,
    val isProrated: Boolean = false,
    val categoryName: String? = null,
    val paidCycleKey: String? = null,
    val aliases: List<String> = emptyList(),
    val validityDays: Int? = null
)

object NecessityManager {
    const val KEY_NECESSITIES_JSON = "key_necessities_json"
    const val KEY_NECESSITIES_MIGRATED = "key_necessities_migrated_v1"

    data class SinkingProgress(val saved: Long, val target: Long) {
        val fraction: Float get() = if (target > 0) (saved / target).toFloat().coerceIn(0f, 1f) else 0f
    }

    data class BillSuggestion(
        val payee: String, val amount: Long, val category: String?,
        val frequency: NecessityFrequency, val lastTimestamp: Long, val txnCount: Int,
        val validityDays: Int? = null
    )

    fun currentCycleKey(startDayOfMonth: Int): String {
        val (cycleStart, _) = BillingCycleHelper.getCycleRange(startDayOfMonth)
        val cal = Calendar.getInstance().apply { timeInMillis = cycleStart }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"
    }

    fun matches(item: NecessityItem, payee: String, category: String): Boolean {
        val wordBoundaryRegex = { word: String -> Regex("(?i)\\b${Regex.escape(word)}\\b") }

        return wordBoundaryRegex(item.name).containsMatchIn(payee) ||
                wordBoundaryRegex(item.name).containsMatchIn(category) ||
                item.aliases.any { wordBoundaryRegex(it).containsMatchIn(payee) }
    }

    fun getMonthlyReserveAmount(item: NecessityItem): Long {
        if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
            val days = item.validityDays!!
            return (item.amount * 30.4375 / days).toLong().coerceAtLeast(1L)
        }
        val months = if (item.frequency.months > 0) item.frequency.months else 1
        return item.amount / months
    }

    fun findLastTransaction(item: NecessityItem, txns: List<TransactionEntity>): TransactionEntity? {
        if (item.name.isBlank()) return null
        return txns.asSequence()
            .filter { it.type == TransactionType.DEBIT && matches(item, it.payee, it.category) }
            .maxByOrNull { it.timestamp }
    }

    fun computeEffectiveDueDate(item: NecessityItem, txns: List<TransactionEntity> = emptyList()): Long? {
        val baseTime = item.dueTimestamp ?: findLastTransaction(item, txns)?.timestamp
        val now = System.currentTimeMillis()

        if (baseTime != null) {
            if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
                val cycleMillis = item.validityDays!! * 86_400_000L
                var due = if (item.dueTimestamp != null) baseTime else baseTime + cycleMillis
                var guard = 0
                while (due < now && guard++ < 600) {
                    due += cycleMillis
                }
                return due
            } else {
                val c = Calendar.getInstance().apply { timeInMillis = baseTime }
                val months = if (item.frequency.months > 0) item.frequency.months else 1
                if (item.dueTimestamp == null) c.add(Calendar.MONTH, months)
                var guard = 0
                while (c.timeInMillis < now && guard++ < 600) {
                    c.add(Calendar.MONTH, months)
                }
                return c.timeInMillis
            }
        }

        if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
            return now + (item.validityDays!! * 86_400_000L)
        } else if (item.frequency != NecessityFrequency.MONTHLY) {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, item.frequency.months) }
            return c.timeInMillis
        }
        return null
    }

    fun sinkingFundProgress(item: NecessityItem, now: Long = System.currentTimeMillis()): SinkingProgress? {
        if (!item.isProrated || item.frequency == NecessityFrequency.MONTHLY) return null

        val cycleDays = when {
            item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0 -> item.validityDays!!
            else -> item.frequency.months * 30
        }

        val dueTimestamp = item.dueTimestamp ?: return null
        val due = Calendar.getInstance().apply { timeInMillis = dueTimestamp }
        while (due.timeInMillis < now) {
            if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
                due.add(Calendar.DAY_OF_YEAR, item.validityDays!!)
            } else {
                due.add(Calendar.MONTH, item.frequency.months)
            }
        }

        val millisRemaining = (due.timeInMillis - now).coerceAtLeast(0L)
        val daysRemaining = (millisRemaining / 86_400_000L).toInt()
        val daysElapsed = (cycleDays - daysRemaining).coerceIn(0, cycleDays)

        val saved = (item.amount * daysElapsed.toDouble() / cycleDays).toLong().coerceIn(0L, item.amount)
        return SinkingProgress(saved, item.amount)
    }

    private fun frequencyForGap(gapDays: Long): Pair<NecessityFrequency, Int?>? = when (gapDays) {
        in 20L..32L -> Pair(NecessityFrequency.EVERY_N_DAYS, gapDays.toInt())
        in 50L..60L -> Pair(NecessityFrequency.EVERY_N_DAYS, gapDays.toInt())
        in 80L..100L -> Pair(NecessityFrequency.QUARTERLY, gapDays.toInt())
        in 170L..195L -> Pair(NecessityFrequency.HALF_YEARLY, null)
        in 350L..380L -> Pair(NecessityFrequency.YEARLY, null)
        else -> if (gapDays in 7L..365L) Pair(NecessityFrequency.EVERY_N_DAYS, gapDays.toInt()) else null
    }

    fun cleanBillName(payee: String): String {
        val trimmed = payee.trim()
        val stripped = trimmed.replace(Regex("[\\s\\d]+$"), "")
        return if (stripped.length >= 3) stripped else trimmed
    }

    fun recurringSuggestion(payee: String, txns: List<TransactionEntity>): BillSuggestion? {
        val list = txns.filter { it.type == TransactionType.DEBIT && it.payee.equals(payee, ignoreCase = true) }
        if (list.size < 3) return null
        val sorted = list.sortedByDescending { it.timestamp }
        val gap = sorted.zipWithNext { a, b -> (a.timestamp - b.timestamp) / 86_400_000L }
            .sorted()[(sorted.size - 1) / 2]
        val (freq, days) = frequencyForGap(gap) ?: Pair(NecessityFrequency.MONTHLY, null)
        return BillSuggestion(
            sorted.first().payee, sorted.first().amount,
            sorted.first().category.takeIf { !it.equals("Uncategorized", true) },
            freq, sorted.first().timestamp, list.size,
            validityDays = days
        )
    }

    fun suggestionFor(txn: TransactionEntity, all: List<TransactionEntity>): BillSuggestion =
        recurringSuggestion(txn.payee, all) ?: BillSuggestion(
            txn.payee, txn.amount,
            txn.category.takeIf { !it.equals("Uncategorized", true) },
            NecessityFrequency.MONTHLY, txn.timestamp, 1
        )

    fun itemFrom(s: BillSuggestion): NecessityItem = NecessityItem(
        id = UUID.randomUUID().toString(),
        name = cleanBillName(s.payee),
        amount = s.amount,
        frequency = s.frequency,
        dueTimestamp = if (s.frequency == NecessityFrequency.MONTHLY) null else s.lastTimestamp,
        categoryName = s.category,
        validityDays = s.validityDays
    )

    fun suggestBills(query: String, txns: List<TransactionEntity>, limit: Int = 3): List<BillSuggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return txns.asSequence()
            .filter { it.type == TransactionType.DEBIT && it.payee.contains(q, ignoreCase = true) }
            .groupBy { it.payee }
            .map { (payee, list) ->
                val sorted = list.sortedByDescending { it.timestamp }
                val gap = sorted.zipWithNext { a, b -> (a.timestamp - b.timestamp) / 86_400_000L }
                    .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                val (freq, days) = if (gap == null) Pair(NecessityFrequency.MONTHLY, null) else (frequencyForGap(gap) ?: Pair(NecessityFrequency.MONTHLY, null))
                BillSuggestion(
                    payee, sorted.first().amount,
                    sorted.first().category.takeIf { !it.equals("Uncategorized", true) },
                    freq, sorted.first().timestamp, list.size,
                    validityDays = days
                )
            }
            .sortedByDescending { it.txnCount }
            .take(limit)
    }

    fun loadNecessities(prefs: SharedPreferences): List<NecessityItem> {
        val raw = prefs.getString(KEY_NECESSITIES_JSON, null) ?: return emptyList()
        val parsedList = mutableListOf<NecessityItem>()

        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)

                    val freqStr = obj.optString("frequency", "MONTHLY")
                    val freq = try { NecessityFrequency.valueOf(freqStr) } catch (e: Exception) { NecessityFrequency.MONTHLY }

                    val aliases = mutableListOf<String>()
                    val aliasesArr = obj.optJSONArray("aliases")
                    if (aliasesArr != null) {
                        for (j in 0 until aliasesArr.length()) {
                            val alias = aliasesArr.getString(j).trim()
                            if (alias.isNotBlank()) aliases.add(alias)
                        }
                    }

                    val validityDays = if (obj.has("validityDays")) obj.getInt("validityDays") else null

                    parsedList.add(NecessityItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        amount = obj.getLong("amount"),
                        frequency = freq,
                        dueTimestamp = if (obj.has("dueTimestamp")) obj.getLong("dueTimestamp") else null,
                        isProrated = obj.optBoolean("isProrated", false),
                        categoryName = obj.optString("categoryName").takeIf { it.isNotBlank() },
                        paidCycleKey = obj.optString("paidCycleKey").takeIf { it.isNotBlank() },
                        aliases = aliases,
                        validityDays = validityDays
                    ))
                } catch (e: Exception) {
                    Log.e("NecessityManager", "Failed to parse individual necessity item at index $i", e)
                }
            }
        } catch (e: Exception) {
            Log.e("NecessityManager", "Failed to parse necessities JSON array", e)
        }

        val isPaiseMigrated = prefs.getBoolean("key_necessities_paise_migrated_v3", false)
        if (!isPaiseMigrated && parsedList.isNotEmpty()) {
            val migratedList = parsedList.map { item ->
                if (item.amount in 1L..999999L) {
                    item.copy(amount = item.amount * 100L)
                } else {
                    item
                }
            }
            saveNecessities(prefs, migratedList)
            prefs.edit().putBoolean("key_necessities_paise_migrated_v3", true).apply()
            return migratedList
        }

        return parsedList
    }

    fun saveNecessities(prefs: SharedPreferences, items: List<NecessityItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("amount", item.amount)
                put("frequency", item.frequency.name)
                if (item.dueTimestamp != null) put("dueTimestamp", item.dueTimestamp)
                put("isProrated", item.isProrated)
                item.categoryName?.let { put("categoryName", it) }
                item.paidCycleKey?.let { put("paidCycleKey", it) }
                put("aliases", JSONArray(item.aliases))
                if (item.validityDays != null) put("validityDays", item.validityDays)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_NECESSITIES_JSON, arr.toString()).apply()
    }
}
