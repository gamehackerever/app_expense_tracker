package com.expensetracker.offline.engine.categorizer

import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

object NaiveBayesCategorizer {

    data class Prediction(val category: String, val confidence: Double)

    private const val MIN_EXAMPLES = 10        // don't predict until the user has taught it enough
    private const val MIN_CONFIDENCE = 0.80    // below this, abstain and let the review queue handle it

    // Words that appear in many payees and say nothing about the category
    private val NOISE = setOf(
        "upi", "pvt", "ltd", "limited", "private", "payment", "payments", "pay", "bank",
        "the", "and", "txn", "ref", "vpa", "paytm", "gpay", "phonepe", "bharatpe",
        "ybl", "oksbi", "okhdfcbank", "okicici", "okaxis", "ibl", "axl", "apl"
    )

    private class Model(
        val classWeight: Map<String, Double>,
        val tokenWeight: Map<String, Map<String, Double>>,
        val classTokenTotal: Map<String, Double>,
        val vocab: Set<String>,
        val totalWeight: Double
    )

    private val mutex = Mutex()
    @Volatile private var model: Model? = null
    @Volatile private var trained = false

    /** Call whenever category_rules changes so the next prediction retrains. */
    fun invalidate() { trained = false }

    fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 3 && it !in NOISE }
            .distinct()

    suspend fun predict(payee: String, ruleDao: CategoryRuleDao): Prediction? {
        if (!trained) {
            mutex.withLock {
                if (!trained) {
                    trained = true // set first so an invalidate() during training isn't lost
                    model = train(ruleDao)
                }
            }
        }
        val m = model ?: return null
        return score(m, payee)?.takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    private suspend fun train(ruleDao: CategoryRuleDao): Model? {
        val classWeight = HashMap<String, Double>()
        val tokenWeight = HashMap<String, HashMap<String, Double>>()
        val classTokenTotal = HashMap<String, Double>()
        val vocab = HashSet<String>()
        var examples = 0

        for (rule in ruleDao.getAllRules()) {
            if (rule.category == "Uncategorized") continue
            val tokens = tokenize(rule.merchantPattern)
            if (tokens.isEmpty()) continue

            // Rules the user reinforced count more, but with diminishing returns
            val w = 1.0 + ln(rule.usageCount.coerceAtLeast(1).toDouble())
            examples++
            classWeight[rule.category] = (classWeight[rule.category] ?: 0.0) + w

            val perClass = tokenWeight.getOrPut(rule.category) { HashMap() }
            for (t in tokens) {
                perClass[t] = (perClass[t] ?: 0.0) + w
                classTokenTotal[rule.category] = (classTokenTotal[rule.category] ?: 0.0) + w
                vocab += t
            }
        }

        if (examples < MIN_EXAMPLES || classWeight.size < 2) return null
        return Model(classWeight, tokenWeight, classTokenTotal, vocab, classWeight.values.sum())
    }

    private fun score(m: Model, payee: String): Prediction? {
        // Ignore tokens never seen in training. If nothing is known, abstain.
        val tokens = tokenize(payee).filter { it in m.vocab }
        if (tokens.isEmpty()) return null

        val logScores = m.classWeight.mapValues { (cat, cw) ->
            val counts = m.tokenWeight[cat].orEmpty()
            val denom = (m.classTokenTotal[cat] ?: 0.0) + m.vocab.size
            var s = ln(cw / m.totalWeight)
            for (t in tokens) s += ln(((counts[t] ?: 0.0) + 1.0) / denom) // Laplace smoothing
            s
        }

        val maxLog = logScores.values.maxOrNull() ?: return null
        val probs = logScores.mapValues { exp(it.value - maxLog) }
        val sum = probs.values.sum()
        val best = probs.maxByOrNull { it.value } ?: return null
        return Prediction(best.key, best.value / sum)
    }
}
