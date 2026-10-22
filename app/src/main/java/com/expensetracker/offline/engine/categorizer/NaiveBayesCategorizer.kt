package com.expensetracker.offline.engine.categorizer

import com.expensetracker.offline.data.local.dao.CategoryRuleDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fast-Learning Subword Character 3-Gram + Word Bigram + Domain Stem Multinomial Naive Bayes Categorizer.
 *
 * - Generalizes across merchant variations once at least 2 distinct categories have been learned.
 * - Requires meaningful whole-word/stem overlap (or >= 4 matching subword 3-grams) and posterior
 *   confidence >= 0.72 so unrelated payees never false-match on a single 3-letter substring.
 */
object NaiveBayesCategorizer {

    data class Prediction(val category: String, val confidence: Double)

    private const val MIN_EXAMPLES = 3
    private const val MIN_DISTINCT_CATEGORIES = 2
    private const val MIN_CONFIDENCE = 0.72

    private val NOISE = setOf(
        "upi", "pvt", "ltd", "limited", "private", "payment", "payments", "pay", "bank",
        "the", "and", "txn", "ref", "vpa", "paytm", "gpay", "phonepe", "bharatpe",
        "ybl", "oksbi", "okhdfcbank", "okicici", "okaxis", "ibl", "axl", "apl",
        "transfer", "credit", "debit", "received", "sent", "india"
    )

    private val DOMAIN_STEMS = listOf(
        "mart", "fresh", "kirana", "dairy", "milk", "bakers", "bakery", "sweets", "mithai",
        "cafe", "coffee", "chai", "tea", "foods", "kitchen", "biryani", "hotel", "restaurant",
        "pharma", "medical", "meds", "clinic", "hospital", "dental", "labs", "diagnostics",
        "petrol", "fuels", "fuel", "gas", "toll", "fastag", "motors", "cabs", "travels",
        "metro", "rail", "telecom", "fiber", "broadband", "recharge", "power", "electric",
        "salon", "spa", "fitness", "gym", "sports", "books", "school", "college", "tuition",
        "silks", "fashion", "trends", "jewellers", "gold", "loan", "finance", "finserv"
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
        if (CategoryClassifier.isGenericPayee(text)) return emptyList()
        val words = text.lowercase(Locale.ROOT)
            .split(TOKENIZE_REGEX)
            .filter { it.length >= 2 && it !in NOISE && !it.all { ch -> ch.isDigit() } }
            .distinct()
        if (words.isEmpty()) return emptyList()
        val features = ArrayList<String>(words.size * 5)
        for (w in words) {
            features.add(w)
            // Extract known Indian merchant stems from concatenated UPI handles
            if (w.length >= 6) {
                for (stem in DOMAIN_STEMS) {
                    if (w != stem && w.contains(stem)) {
                        features.add("s:$stem")
                    }
                }
            }
            // Add character 3-grams for words of length >= 4 so short names don't collide
            if (w.length >= 4) {
                val padded = "#$w#"
                for (i in 0..padded.length - 3) {
                    features.add("g:${padded.substring(i, i + 3)}")
                }
            }
        }
        // Add adjacent word bigrams for multi-word merchant names
        for (i in 0 until (words.size - 1)) {
            features.add("b:${words[i]}_${words[i + 1]}")
        }
        return features.distinct()
    }

    suspend fun predict(payee: String, ruleDao: CategoryRuleDao): Prediction? {
        if (CategoryClassifier.isGenericPayee(payee)) return null
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
            if (rule.category.equals("Uncategorized", ignoreCase = true) ||
                rule.category.equals("Transfer", ignoreCase = true) ||
                rule.category == "Split Settlement"
            ) continue
            if (CategoryClassifier.isGenericPayee(rule.merchantPattern)) continue
            val tokens = tokenize(rule.merchantPattern)
            if (tokens.isEmpty()) continue

            val w = 1.0 + ln(rule.usageCount.coerceAtLeast(1).toDouble())
            examples++
            classWeight[rule.category] = (classWeight[rule.category] ?: 0.0) + w

            val perClass = tokenWeight.getOrPut(rule.category) { HashMap() }
            for (t in tokens) {
                val featureWeight = when {
                    t.startsWith("g:") -> w * 0.35
                    t.startsWith("s:") -> w * 0.90
                    t.startsWith("b:") -> w * 1.15
                    else -> w
                }
                perClass[t] = (perClass[t] ?: 0.0) + featureWeight
                classTokenTotal[rule.category] = (classTokenTotal[rule.category] ?: 0.0) + featureWeight
                vocab += t
            }
        }

        if (examples < MIN_EXAMPLES || classWeight.size < MIN_DISTINCT_CATEGORIES) return null
        return Model(classWeight, tokenWeight, classTokenTotal, vocab, classWeight.values.sum())
    }

    private fun score(m: Model, payee: String): Prediction? {
        if (m.classWeight.size < MIN_DISTINCT_CATEGORIES) return null
        val tokens = tokenize(payee).filter { it in m.vocab }
        if (tokens.isEmpty()) return null

        val hasWordOrStem = tokens.any { !it.startsWith("g:") }
        val ngramCount = tokens.count { it.startsWith("g:") }
        if (!hasWordOrStem && ngramCount < 4) return null

        val logScores = m.classWeight.mapValues { (cat, cw) ->
            val counts = m.tokenWeight[cat].orEmpty()
            val denom = (m.classTokenTotal[cat] ?: 0.0) + m.vocab.size * 0.5
            var s = ln(cw / m.totalWeight)
            for (t in tokens) {
                val weight = when {
                    t.startsWith("g:") -> 0.35
                    t.startsWith("s:") -> 0.90
                    t.startsWith("b:") -> 1.15
                    else -> 1.0
                }
                s += weight * ln(((counts[t] ?: 0.0) + 0.5) / denom)
            }
            s
        }

        val maxLog = logScores.values.maxOrNull() ?: return null
        val probs = logScores.mapValues { exp(it.value - maxLog) }
        val sum = probs.values.sum()
        if (sum <= 0.0) return null
        val best = probs.maxByOrNull { it.value } ?: return null
        return Prediction(best.key, best.value / sum)
    }
}
