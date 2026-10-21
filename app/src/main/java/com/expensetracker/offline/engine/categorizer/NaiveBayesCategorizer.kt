package com.expensetracker.offline.engine.categorizer

import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fast-Learning Subword Character 3-Gram + Word Token Multinomial Naive Bayes Categorizer.
 *
 * Upgraded to learn within the first few days of use:
 * - Lowers [MIN_EXAMPLES] from 10 to 2 so user-taught categories begin generalizing immediately.
 * - Extracts both whole-word tokens AND character 3-grams (`#ma`, `mam`, `ama`, `mar`, `art`, `rt#`),
 *   enabling zero-config generalization across merchant name variations, VPA handles, and prefixes.
 */
object NaiveBayesCategorizer {

    data class Prediction(val category: String, val confidence: Double)

    private const val MIN_EXAMPLES = 2         // starts generalizing after just 2 taught rules
    private const val MIN_CONFIDENCE = 0.68    // balanced threshold for subword + word ensemble

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

    private val TOKENIZE_REGEX = Regex("[^\\p{L}\\p{N}]+")

    fun tokenize(text: String): List<String> {
        val words = text.lowercase(Locale.ROOT)
            .split(TOKENIZE_REGEX)
            .filter { it.length >= 2 && it !in NOISE }
            .distinct()
        val features = ArrayList<String>(words.size * 4)
        for (w in words) {
            features.add(w)
            // Add character 3-grams with boundary markers so "mamart" and "ma mart" share subwords
            if (w.length >= 3) {
                val padded = "#$w#"
                for (i in 0..padded.length - 3) {
                    features.add("g:${padded.substring(i, i + 3)}")
                }
            }
        }
        return features.distinct()
    }

    suspend fun predict(payee: String, ruleDao: CategoryRuleDao): Prediction? {
        if (!trained) {
            mutex.withLock {
                if (!trained) {
                    trained = true
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

            val w = 1.0 + ln(rule.usageCount.coerceAtLeast(1).toDouble())
            examples++
            classWeight[rule.category] = (classWeight[rule.category] ?: 0.0) + w

            val perClass = tokenWeight.getOrPut(rule.category) { HashMap() }
            for (t in tokens) {
                val featureWeight = if (t.startsWith("g:")) w * 0.45 else w
                perClass[t] = (perClass[t] ?: 0.0) + featureWeight
                classTokenTotal[rule.category] = (classTokenTotal[rule.category] ?: 0.0) + featureWeight
                vocab += t
            }
        }

        if (examples < MIN_EXAMPLES || classWeight.isEmpty()) return null
        return Model(classWeight, tokenWeight, classTokenTotal, vocab, classWeight.values.sum())
    }

    private fun score(m: Model, payee: String): Prediction? {
        val tokens = tokenize(payee).filter { it in m.vocab }
        if (tokens.isEmpty()) return null

        val logScores = m.classWeight.mapValues { (cat, cw) ->
            val counts = m.tokenWeight[cat].orEmpty()
            val denom = (m.classTokenTotal[cat] ?: 0.0) + m.vocab.size * 0.5
            var s = ln(cw / m.totalWeight)
            for (t in tokens) {
                val weight = if (t.startsWith("g:")) 0.45 else 1.0
                s += weight * ln(((counts[t] ?: 0.0) + 0.5) / denom)
            }
            s
        }

        val maxLog = logScores.values.maxOrNull() ?: return null
        val probs = logScores.mapValues { exp(it.value - maxLog) }
        val sum = probs.values.sum()
        val best = probs.maxByOrNull { it.value } ?: return null
        return Prediction(best.key, if (m.classWeight.size == 1) 0.85 else best.value / sum)
    }
}
