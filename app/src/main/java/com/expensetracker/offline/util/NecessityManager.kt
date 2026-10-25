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
    const val KEY_NECESSITIES_PAISE_MIGRATED = "key_necessities_paise_migrated_v3"

    data class SinkingProgress(val saved: Long, val target: Long) {
        val fraction: Float get() = if (target > 0) (saved.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
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

    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun getWordBoundaryRegex(word: String): Regex {
        val key = word.trim().lowercase()
        if (regexCache.size > 256) regexCache.clear()
        return regexCache.getOrPut(key) {
            Regex("(?i)\\b${Regex.escape(key)}\\b")
        }
    }

    fun matches(item: NecessityItem, payee: String, category: String): Boolean {
        if (item.name.isBlank()) return false
        val nameRegex = getWordBoundaryRegex(item.name)
        if (nameRegex.containsMatchIn(payee)) return true
        if (item.aliases.any { alias -> alias.isNotBlank() && getWordBoundaryRegex(alias).containsMatchIn(payee) }) return true
        return item.aliases.isEmpty() && item.name.trim().equals(category.trim(), ignoreCase = true)
    }

    fun getMonthlyReserveAmount(item: NecessityItem): Long {
        if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
            val days = (item.validityDays ?: 0)
            return (item.amount * 30.4375 / days).toLong().coerceAtLeast(1L)
        }
        val months = if (item.frequency.months > 0) item.frequency.months else 1
        return item.amount / months
    }

    fun findLastTransaction(item: NecessityItem, txns: List<TransactionEntity>): TransactionEntity? {
        if (item.name.isBlank() || txns.isEmpty()) return null
        val isDescending = txns.size < 2 || txns[0].timestamp >= txns[txns.size - 1].timestamp
        return if (isDescending) {
            txns.firstOrNull { it.type == TransactionType.DEBIT && matches(item, it.payee, it.category) }
        } else {
            txns.asSequence()
                .filter { it.type == TransactionType.DEBIT && matches(item, it.payee, it.category) }
                .maxByOrNull { it.timestamp }
        }
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun computeEffectiveDueDate(item: NecessityItem, txns: List<TransactionEntity> = emptyList()): Long? {
        val lastTxnTime = findLastTransaction(item, txns)?.timestamp
        val manualDue = item.dueTimestamp
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)

        if (lastTxnTime != null && (manualDue == null || lastTxnTime > manualDue)) {
            return if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
                lastTxnTime + (item.validityDays ?: 0) * 86_400_000L
            } else {
                val months = if (item.frequency.months > 0) item.frequency.months else 1
                Calendar.getInstance().apply {
                    timeInMillis = lastTxnTime
                    add(Calendar.MONTH, months)
                }.timeInMillis
            }
        }

        if (manualDue != null) {
            if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
                val cycleMillis = (item.validityDays ?: 0) * 86_400_000L
                var due = manualDue
                var guard = 0
                while (startOfDay(due) < todayStart - cycleMillis && guard++ < 600) {
                    due += cycleMillis
                }
                return due
            } else {
                val c = Calendar.getInstance().apply { timeInMillis = manualDue }
                val months = if (item.frequency.months > 0) item.frequency.months else 1
                val oneCycleAgo = Calendar.getInstance().apply {
                    timeInMillis = todayStart
                    add(Calendar.MONTH, -months)
                }.timeInMillis
                var guard = 0
                while (startOfDay(c.timeInMillis) < oneCycleAgo && guard++ < 600) {
                    c.add(Calendar.MONTH, months)
                }
                return c.timeInMillis
            }
        }

        if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
            return now + ((item.validityDays ?: 0) * 86_400_000L)
        } else if (item.frequency != NecessityFrequency.MONTHLY) {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, item.frequency.months) }
            return c.timeInMillis
        }
        return null
    }

    fun sinkingFundProgress(item: NecessityItem, now: Long = System.currentTimeMillis()): SinkingProgress? {
        if (!item.isProrated || item.frequency == NecessityFrequency.MONTHLY) return null

        val cycleDays = when {
            item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0 -> (item.validityDays ?: 0)
            else -> item.frequency.months * 30
        }

        val dueTimestamp = item.dueTimestamp ?: return null
        val due = Calendar.getInstance().apply { timeInMillis = dueTimestamp }
        while (due.timeInMillis < now) {
            if (item.frequency == NecessityFrequency.EVERY_N_DAYS && (item.validityDays ?: 0) > 0) {
                due.add(Calendar.DAY_OF_YEAR, (item.validityDays ?: 0))
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
        if (payee.isBlank() || txns.size < 3) return null
        val list = txns.asSequence()
            .filter { it.type == TransactionType.DEBIT && it.payee.equals(payee, ignoreCase = true) }
            .take(24)
            .toList()
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

    private val RECURRING_BILL_CATEGORIES = setOf("bills & utilities", "subscription", "subscriptions", "rent", "emi", "insurance")

    fun suggestionFor(txn: TransactionEntity, all: List<TransactionEntity>): BillSuggestion? {
        recurringSuggestion(txn.payee, all)?.let { return it }
        val catLower = txn.category.trim().lowercase()
        return if (catLower in RECURRING_BILL_CATEGORIES) {
            BillSuggestion(
                txn.payee, txn.amount,
                txn.category.takeIf { !it.equals("Uncategorized", true) },
                NecessityFrequency.MONTHLY, txn.timestamp, 1
            )
        } else null
    }

    fun itemFrom(s: BillSuggestion): NecessityItem = NecessityItem(
        id = UUID.randomUUID().toString(),
        name = cleanBillName(s.payee),
        amount = s.amount,
        frequency = s.frequency,
        dueTimestamp = if (s.frequency == NecessityFrequency.MONTHLY) null else s.lastTimestamp,
        categoryName = s.category,
        validityDays = s.validityDays
    )

    fun suggestBills(query: String, txns: List<TransactionEntity>, limit: Int = 4): List<BillSuggestion> {
        val q = query.trim()
        return txns.asSequence()
            .filter {
                it.type == TransactionType.DEBIT &&
                        !it.excludeFromSpend &&
                        it.payee.isNotBlank() &&
                        !it.payee.equals("Unknown", ignoreCase = true) &&
                        (q.isEmpty() || it.payee.contains(q, ignoreCase = true))
            }
            .groupBy { it.payee.trim() }
            .filter { (payee, list) ->
                if (q.isNotEmpty()) true
                else list.size >= 2 || list.first().category.lowercase() in RECURRING_BILL_CATEGORIES ||
                        Regex("(?i)\\b(recharge|vi|jio|airtel|bsnl|spotify|netflix|youtube|google|gym|rent|wifi|broadband|electricity)\\b").containsMatchIn(payee)
            }
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
            .sortedWith(
                compareByDescending<BillSuggestion> { it.category?.lowercase() in RECURRING_BILL_CATEGORIES }
                    .thenByDescending { it.txnCount }
                    .thenByDescending { it.lastTimestamp }
            )
            .take(limit)
    }

    fun loadNecessities(prefs: SharedPreferences): List<NecessityItem> {
        val isPaiseMigrated = prefs.getBoolean(KEY_NECESSITIES_PAISE_MIGRATED, false)
        val raw = prefs.getString(KEY_NECESSITIES_JSON, null)
        if (raw.isNullOrBlank()) {
            // Fresh install: mark paise migration complete immediately so future items saved in paise
            // are never mistaken for legacy rupee items and multiplied by 100.
            if (!isPaiseMigrated) {
                prefs.edit().putBoolean(KEY_NECESSITIES_PAISE_MIGRATED, true).apply()
            }
            return emptyList()
        }

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

        if (!isPaiseMigrated) {
            if (parsedList.isEmpty()) {
                prefs.edit().putBoolean(KEY_NECESSITIES_PAISE_MIGRATED, true).apply()
                return emptyList()
            }
            val migratedList = parsedList.map { item ->
                if (item.amount in 1L..999999L) {
                    item.copy(amount = item.amount * 100L)
                } else {
                    item
                }
            }
            saveNecessities(prefs, migratedList)
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
        prefs.edit()
            .putString(KEY_NECESSITIES_JSON, arr.toString())
            .putBoolean(KEY_NECESSITIES_PAISE_MIGRATED, true)
            .apply()
    }

    data class UpcomingBillAlert(
        val item: NecessityItem,
        val dueMillis: Long,
        val daysUntilDue: Int,
        val remainingAmountPaise: Long
    )

    private fun startOfDayMillis(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun estimateMonthlyBillDue(item: NecessityItem, lastPaidMillis: Long?, nowMillis: Long): Long? {
        val cal = Calendar.getInstance()
        when {
            item.dueTimestamp != null -> cal.timeInMillis = item.dueTimestamp
            lastPaidMillis != null -> {
                cal.timeInMillis = lastPaidMillis
                cal.add(Calendar.MONTH, 1)
            }
            else -> return null
        }
        val today = startOfDayMillis(nowMillis)
        var guard = 0
        while (cal.timeInMillis < today && guard++ < 600) {
            cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Returns all fixed costs / necessities that are unpaid in the current billing cycle and are due
     * within [maxDaysAhead] days (default 2 days prior, 1 day prior, due today, or 1 day overdue).
     */
    fun getDueBillAlerts(
        prefs: SharedPreferences,
        txns: List<TransactionEntity>,
        startDayOfMonth: Int,
        nowMillis: Long = System.currentTimeMillis(),
        maxDaysAhead: Int = 2
    ): List<UpcomingBillAlert> {
        val items = loadNecessities(prefs)
        if (items.isEmpty()) return emptyList()

        val (cycleStart, _) = BillingCycleHelper.getCycleRange(startDayOfMonth)
        val cycleKey = currentCycleKey(startDayOfMonth)
        val todayStart = startOfDayMillis(nowMillis)

        val alerts = mutableListOf<UpcomingBillAlert>()
        for (item in items) {
            if (item.paidCycleKey == cycleKey) continue

            val targetAmount = if (item.frequency == NecessityFrequency.MONTHLY || !item.isProrated) {
                item.amount
            } else {
                getMonthlyReserveAmount(item)
            }
            if (targetAmount <= 0L) continue

            val paidInCycle = txns.asSequence()
                .filter {
                    it.type == TransactionType.DEBIT &&
                            !it.excludeFromSpend &&
                            it.timestamp >= cycleStart &&
                            matches(item, it.payee, it.category)
                }
                .sumOf { it.amount }

            if (paidInCycle >= targetAmount) continue

            val lastMatchedTs = findLastTransaction(item, txns)?.timestamp
            val dueMillis = computeEffectiveDueDate(item, txns)
                ?: if (item.frequency == NecessityFrequency.MONTHLY) {
                    estimateMonthlyBillDue(item, lastMatchedTs, nowMillis)
                } else null

            if (dueMillis != null) {
                val dueDayStart = startOfDayMillis(dueMillis)
                val daysUntil = Math.round((dueDayStart - todayStart) / 86_400_000.0).toInt()
                if (daysUntil in -1..maxDaysAhead) {
                    alerts.add(
                        UpcomingBillAlert(
                            item = item,
                            dueMillis = dueMillis,
                            daysUntilDue = daysUntil,
                            remainingAmountPaise = (targetAmount - paidInCycle).coerceAtLeast(0L)
                        )
                    )
                }
            }
        }
        return alerts.sortedBy { it.daysUntilDue }
    }
}
