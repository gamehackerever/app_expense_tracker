package com.expensetracker.offline.engine.ml

import android.content.Context
import android.util.Log
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Active On-Device SMS & Notification Pattern Learner (`SmsPatternLearner`).
 *
 * Learns directly from every Review Queue approval and disapproval (discard) so the app
 * becomes smarter with every single tap:
 *
 * 1. **Structural Skeleton Extraction (`extractSkeleton`)**:
 *    Normalizes raw SMS/Notification text into a structural template signature by replacing
 *    currency amounts (`<AMT>`), masked account/card numbers (`<ACCT>`), reference/UTR IDs (`<REF>`),
 *    dates/times (`<DATE>`), VPAs (`<VPA>`), and numbers (`<NUM>`).
 *
 * 2. **Disapproval Learning (`learnDiscardedReview`)**:
 *    When a user discards a review item (single or bulk), records negative template evidence for
 *    `(senderKey, skeleton)` and tracks sender-level noise statistics. Future messages matching a
 *    discarded template are automatically rejected by `FinancialParser` before reaching the Review Queue.
 *
 * 3. **Approval Learning (`learnApprovedReview`)**:
 *    When a user approves a review item (single or bulk) or edits an SMS/notification transaction:
 *    - Marks `(senderKey, skeleton)` as a confirmed transaction template (`approvedCount++`).
 *    - Learns dynamic payee extraction anchors (`prefixAnchor`, `suffixAnchor`) if the resolved payee
 *      appears inside the raw message body, allowing future messages of the same template to extract
 *      new merchant names from the same structural slot.
 *    - Learns UPI VPA handle mappings (`vpa -> (payee, category)`).
 *    - IMPORTANT: Bank SMS templates and bank senders are shared across all merchants and categories,
 *      so categories are ONLY learned at the specific VPA level (never stamped onto a bank SMS skeleton
 *      or bank sender ID).
 */
object SmsPatternLearner {

    private const val TAG = "SmsPatternLearner"
    private const val STATE_FILE_NAME = "sms_pattern_learner_v1.json"
    private const val MAX_TEMPLATES = 600
    private const val MAX_SENDERS = 300
    private const val MAX_VPAS = 500
    private const val ANY_SENDER = "*"

    data class TemplateRecord(
        val senderKey: String,
        val skeleton: String,
        var approvedCount: Int = 0,
        var rejectedCount: Int = 0,
        var learnedType: String? = null,
        var learnedCategory: String? = null,
        var learnedBankName: String? = null,
        var fallbackPayee: String? = null,
        var prefixAnchor: String? = null,
        var suffixAnchor: String? = null,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    data class SenderRecord(
        val senderKey: String,
        var approvedCount: Int = 0,
        var rejectedCount: Int = 0,
        var learnedBankName: String? = null,
        var defaultPayee: String? = null,
        var defaultCategory: String? = null,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    data class VpaRecord(
        val vpa: String,
        var payee: String,
        var category: String,
        var usageCount: Int = 1,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    data class TemplateEvaluation(
        val isBlockedNonTransaction: Boolean = false,
        val isConfirmedTransaction: Boolean = false,
        val inferredPayee: String? = null,
        val inferredCategory: String? = null,
        val inferredType: TransactionType? = null,
        val inferredBankName: String? = null,
        val confidence: Float = 0f
    )

    private val templates = ConcurrentHashMap<String, TemplateRecord>()
    private val senders = ConcurrentHashMap<String, SenderRecord>()
    private val vpaMemory = ConcurrentHashMap<String, VpaRecord>()

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null
    @Volatile private var isInitialized = false
    @Volatile private var pendingSaveJob: Job? = null

    private val URL_REGEX = Pattern.compile("https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE)
    private val VPA_REGEX = Pattern.compile("\\b([a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,})\\b")
    private val AMOUNT_PRE_REGEX = Pattern.compile(
        "(?:rs\\.?|inr|₹|amt|amount)\\s*:?\\s*[\\d,]+(?:\\.\\d{1,2})?",
        Pattern.CASE_INSENSITIVE
    )
    private val AMOUNT_POST_REGEX = Pattern.compile(
        "[\\d,]+(?:\\.\\d{1,2})?\\s*(?:rs\\.?|inr|₹)",
        Pattern.CASE_INSENSITIVE
    )
    private val ACCT_REGEX = Pattern.compile(
        "(?:a/c|acct|account|card)\\s*(?:no\\.?|number|ending(?:\\s+in|\\s+with)?|xx+|\\*+)?\\s*[:\\-]?\\s*[xX*#\\d]{3,16}|\\b[xX*]{2,}\\d{3,6}\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val DATE_TIME_REGEX = Pattern.compile(
        "\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b\\d{1,2}[\\s-](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:[\\s-]\\d{2,4})?\\b|\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[ap]m)?\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val REF_REGEX = Pattern.compile("\\b\\d{8,18}\\b|\\b(?=[a-zA-Z0-9]*\\d{4,})[a-zA-Z0-9]{10,22}\\b")
    private val NUM_REGEX = Pattern.compile("\\b\\d+(?:,\\d+)*(?:\\.\\d+)?\\b")
    private val MULTI_SPACE_REGEX = Pattern.compile("\\s+")
    private val DLT_PREFIX_REGEX = Pattern.compile("^[A-Z]{2}-|-[A-Z]$")

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            appContext = context.applicationContext
            loadFromDisk()
            sanitizePoisonedPatterns()
            isInitialized = true
        }
    }

    /**
     * Cleans any poisoned template/sender category overrides or generic fallback payees that
     * were persisted in earlier builds so bank SMS templates never force a single category like "Travel".
     */
    fun sanitizePoisonedPatterns() {
        var modified = false
        for (rec in templates.values) {
            synchronized(rec) {
                if (rec.learnedCategory != null) {
                    rec.learnedCategory = null
                    modified = true
                }
                if (rec.fallbackPayee != null && CategoryClassifier.isGenericPayee(rec.fallbackPayee)) {
                    rec.fallbackPayee = null
                    modified = true
                }
            }
        }
        for (sRec in senders.values) {
            synchronized(sRec) {
                if (sRec.defaultCategory != null) {
                    sRec.defaultCategory = null
                    modified = true
                }
                if (sRec.defaultPayee != null && CategoryClassifier.isGenericPayee(sRec.defaultPayee)) {
                    sRec.defaultPayee = null
                    modified = true
                }
            }
        }
        val vpaIter = vpaMemory.entries.iterator()
        while (vpaIter.hasNext()) {
            val entry = vpaIter.next()
            val vRec = entry.value
            if (CategoryClassifier.isGenericPayee(vRec.payee)) {
                vpaIter.remove()
                modified = true
            } else if (vRec.category.equals("Travel", ignoreCase = true) &&
                CategoryClassifier.classifySync(vRec.payee, vRec.vpa) != "Travel"
            ) {
                vRec.category = CategoryClassifier.classifySync(vRec.payee, vRec.vpa)
                modified = true
            }
        }
        if (modified) {
            scheduleSave()
        }
    }

    fun normalizeSenderKey(sender: String?): String {
        if (sender.isNullOrBlank()) return ANY_SENDER
        var clean = sender.trim().uppercase(Locale.ROOT)
        clean = DLT_PREFIX_REGEX.matcher(clean).replaceAll("")
        if (clean.startsWith("+91") && clean.length > 10) {
            clean = clean.removePrefix("+91")
        }
        clean = clean.replace(Regex("[^A-Z0-9._]"), "")
        return clean.ifBlank { ANY_SENDER }
    }

    fun extractSkeleton(rawContent: String): String {
        if (rawContent.isBlank()) return ""
        var s = rawContent.trim().lowercase(Locale.ROOT)
        s = URL_REGEX.matcher(s).replaceAll("<url>")
        s = VPA_REGEX.matcher(s).replaceAll("<vpa>")
        s = AMOUNT_PRE_REGEX.matcher(s).replaceAll("<amt>")
        s = AMOUNT_POST_REGEX.matcher(s).replaceAll("<amt>")
        s = ACCT_REGEX.matcher(s).replaceAll("<acct>")
        s = DATE_TIME_REGEX.matcher(s).replaceAll("<date>")
        s = REF_REGEX.matcher(s).replaceAll("<ref>")
        s = NUM_REGEX.matcher(s).replaceAll("<num>")
        s = s.replace(Regex("[^a-z<>\\s/:.-]"), " ")
        s = MULTI_SPACE_REGEX.matcher(s).replaceAll(" ").trim()
        return if (s.length > 240) s.substring(0, 240) else s
    }

    fun extractVpa(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = VPA_REGEX.matcher(text)
        return if (m.find()) m.group(1)?.lowercase(Locale.ROOT) else null
    }

    fun lookupVpa(text: String?): VpaRecord? {
        val vpa = extractVpa(text) ?: return null
        return vpaMemory[vpa]
    }

    private fun compositeKey(senderKey: String, skeleton: String): String = "$senderKey||$skeleton"

    fun learnApprovedReview(
        sender: String?,
        rawContent: String,
        resolvedPayee: String,
        resolvedCategory: String,
        resolvedType: TransactionType,
        resolvedBankName: String? = null
    ) {
        if (rawContent.isBlank() || rawContent == "Manual Entry") return
        val senderKey = normalizeSenderKey(sender)
        val skeleton = extractSkeleton(rawContent)
        if (skeleton.isBlank()) return

        val cleanPayee = resolvedPayee.trim().takeIf {
            it.isNotBlank() && !CategoryClassifier.isGenericPayee(it)
        }
        val cleanCategory = resolvedCategory.trim().takeIf {
            it.isNotBlank() &&
                !it.equals("Uncategorized", ignoreCase = true) &&
                !it.equals("Transfer", ignoreCase = true) &&
                it != "Split Settlement"
        }
        val cleanBank = resolvedBankName?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Unknown Bank", ignoreCase = true)
        }

        // VPA handle is specific to a single merchant/recipient, so VPA -> (payee, category) is safe.
        val vpa = extractVpa(rawContent)
        if (vpa != null && (cleanPayee != null || cleanCategory != null)) {
            val existingVpa = vpaMemory[vpa]
            vpaMemory[vpa] = VpaRecord(
                vpa = vpa,
                payee = cleanPayee ?: existingVpa?.payee ?: vpa.substringBefore("@"),
                category = if (resolvedType == TransactionType.DEBIT) {
                    cleanCategory ?: existingVpa?.category ?: "Uncategorized"
                } else {
                    existingVpa?.category ?: "Uncategorized"
                },
                usageCount = (existingVpa?.usageCount ?: 0) + 1,
                lastUpdated = System.currentTimeMillis()
            )
        }

        val anchors = if (cleanPayee != null) extractPayeeAnchors(rawContent, cleanPayee) else null

        val key = compositeKey(senderKey, skeleton)
        val record = templates.getOrPut(key) { TemplateRecord(senderKey = senderKey, skeleton = skeleton) }
        synchronized(record) {
            record.approvedCount = (record.approvedCount + 1).coerceAtMost(1000)
            if (record.rejectedCount > 0 && record.approvedCount >= record.rejectedCount) {
                record.rejectedCount = 0
            }
            record.learnedType = resolvedType.name
            if (cleanBank != null) {
                record.learnedBankName = cleanBank
            }
            // Only store dynamic structural anchors (never hardcode a single merchant or category onto a bank SMS template)
            if (anchors != null) {
                record.prefixAnchor = anchors.first
                record.suffixAnchor = anchors.second
            }
            record.lastUpdated = System.currentTimeMillis()
        }

        val wildcardKey = compositeKey(ANY_SENDER, skeleton)
        val wildcardRecord = templates.getOrPut(wildcardKey) { TemplateRecord(senderKey = ANY_SENDER, skeleton = skeleton) }
        synchronized(wildcardRecord) {
            wildcardRecord.approvedCount = (wildcardRecord.approvedCount + 1).coerceAtMost(1000)
            if (wildcardRecord.rejectedCount > 0 && wildcardRecord.approvedCount >= wildcardRecord.rejectedCount) {
                wildcardRecord.rejectedCount = 0
            }
            wildcardRecord.learnedType = resolvedType.name
            if (anchors != null) {
                wildcardRecord.prefixAnchor = anchors.first
                wildcardRecord.suffixAnchor = anchors.second
            }
            wildcardRecord.lastUpdated = System.currentTimeMillis()
        }

        if (senderKey != ANY_SENDER) {
            val sRec = senders.getOrPut(senderKey) { SenderRecord(senderKey = senderKey) }
            synchronized(sRec) {
                sRec.approvedCount = (sRec.approvedCount + 1).coerceAtMost(5000)
                if (cleanBank != null) sRec.learnedBankName = cleanBank
                sRec.lastUpdated = System.currentTimeMillis()
            }
        }

        scheduleSave()
    }

    fun learnDiscardedReview(sender: String?, rawContent: String) {
        if (rawContent.isBlank() || rawContent.startsWith("Silent Deduction", ignoreCase = true)) return
        val senderKey = normalizeSenderKey(sender)
        val skeleton = extractSkeleton(rawContent)
        if (skeleton.isBlank()) return

        val key = compositeKey(senderKey, skeleton)
        val record = templates.getOrPut(key) { TemplateRecord(senderKey = senderKey, skeleton = skeleton) }
        synchronized(record) {
            record.rejectedCount = (record.rejectedCount + 1).coerceAtMost(1000)
            record.lastUpdated = System.currentTimeMillis()
        }

        val wildcardKey = compositeKey(ANY_SENDER, skeleton)
        val wildcardRecord = templates.getOrPut(wildcardKey) { TemplateRecord(senderKey = ANY_SENDER, skeleton = skeleton) }
        synchronized(wildcardRecord) {
            wildcardRecord.rejectedCount = (wildcardRecord.rejectedCount + 1).coerceAtMost(1000)
            wildcardRecord.lastUpdated = System.currentTimeMillis()
        }

        if (senderKey != ANY_SENDER) {
            val sRec = senders.getOrPut(senderKey) { SenderRecord(senderKey = senderKey) }
            synchronized(sRec) {
                sRec.rejectedCount = (sRec.rejectedCount + 1).coerceAtMost(5000)
                sRec.lastUpdated = System.currentTimeMillis()
            }
        }

        scheduleSave()
    }

    fun evaluate(sender: String?, rawContent: String): TemplateEvaluation {
        if (rawContent.isBlank()) return TemplateEvaluation()
        val senderKey = normalizeSenderKey(sender)
        val skeleton = extractSkeleton(rawContent)
        if (skeleton.isBlank()) return TemplateEvaluation()

        val exactMatch = templates[compositeKey(senderKey, skeleton)]
        val wildcardMatch = templates[compositeKey(ANY_SENDER, skeleton)]
        val senderProfile = if (senderKey != ANY_SENDER) senders[senderKey] else null
        val vpaMatch = lookupVpa(rawContent)

        val activeTemplate = exactMatch ?: wildcardMatch

        if (activeTemplate != null) {
            val approved = activeTemplate.approvedCount
            val rejected = activeTemplate.rejectedCount

            if (rejected > approved) {
                return TemplateEvaluation(
                    isBlockedNonTransaction = true,
                    isConfirmedTransaction = false,
                    confidence = (rejected.toFloat() / (rejected + approved + 1).toFloat()).coerceIn(0.65f, 0.99f)
                )
            }

            if (approved > 0 && approved >= rejected) {
                val dynamicPayee = vpaMatch?.payee?.takeIf { it.isNotBlank() && !CategoryClassifier.isGenericPayee(it) }
                    ?: extractPayeeUsingAnchors(
                        rawContent = rawContent,
                        prefixAnchor = activeTemplate.prefixAnchor,
                        suffixAnchor = activeTemplate.suffixAnchor
                    )?.takeIf { !CategoryClassifier.isGenericPayee(it) }

                val learnedType = activeTemplate.learnedType?.let {
                    runCatching { TransactionType.valueOf(it) }.getOrNull()
                }
                // Only infer category from an exact VPA match — never from a general bank SMS template!
                val bestCategory = vpaMatch?.category?.takeIf { !it.equals("Uncategorized", ignoreCase = true) }

                return TemplateEvaluation(
                    isBlockedNonTransaction = false,
                    isConfirmedTransaction = true,
                    inferredPayee = dynamicPayee,
                    inferredCategory = bestCategory,
                    inferredType = learnedType,
                    inferredBankName = activeTemplate.learnedBankName ?: senderProfile?.learnedBankName,
                    confidence = (0.80f + 0.04f * approved.coerceAtMost(5)).coerceAtMost(0.99f)
                )
            }
        }

        if (vpaMatch != null) {
            return TemplateEvaluation(
                isBlockedNonTransaction = false,
                isConfirmedTransaction = false,
                inferredPayee = vpaMatch.payee.takeIf { !CategoryClassifier.isGenericPayee(it) },
                inferredCategory = vpaMatch.category.takeIf { !it.equals("Uncategorized", ignoreCase = true) },
                inferredBankName = senderProfile?.learnedBankName,
                confidence = 0.85f
            )
        }

        if (senderProfile != null) {
            if (senderProfile.rejectedCount >= 4 && senderProfile.approvedCount == 0 && senderProfile.learnedBankName == null) {
                return TemplateEvaluation(
                    isBlockedNonTransaction = true,
                    isConfirmedTransaction = false,
                    confidence = 0.75f
                )
            }
            if (senderProfile.learnedBankName != null) {
                return TemplateEvaluation(
                    isBlockedNonTransaction = false,
                    isConfirmedTransaction = false,
                    inferredBankName = senderProfile.learnedBankName,
                    inferredCategory = null,
                    confidence = 0.45f
                )
            }
        }

        return TemplateEvaluation()
    }

    private fun extractPayeeAnchors(rawContent: String, payee: String): Pair<String, String?>? {
        val cleanText = rawContent.replace("\n", " ").replace(MULTI_SPACE_REGEX.toRegex(), " ").trim()
        val idx = cleanText.indexOf(payee, ignoreCase = true)
        if (idx <= 0) return null

        val before = cleanText.substring(0, idx).trimEnd()
        val after = cleanText.substring(idx + payee.length).trimStart()

        val rawPrefixWords = before.split(" ").filter { it.isNotBlank() }.takeLast(2)
        val cleanPrefixWords = if (rawPrefixWords.any { w -> w.any { it.isDigit() } }) {
            rawPrefixWords.filter { w -> !w.any { it.isDigit() } }
        } else {
            rawPrefixWords
        }
        if (cleanPrefixWords.isEmpty()) return null
        val prefixAnchor = cleanPrefixWords.joinToString(" ").lowercase(Locale.ROOT)

        val suffixAnchor = after.split(Regex("\\s+")).firstOrNull()?.takeIf {
            it.isNotBlank() && !it.any { ch -> ch.isDigit() }
        }?.lowercase(Locale.ROOT)
        return prefixAnchor to suffixAnchor
    }

    private fun extractPayeeUsingAnchors(
        rawContent: String,
        prefixAnchor: String?,
        suffixAnchor: String?
    ): String? {
        if (prefixAnchor.isNullOrBlank()) return null
        val cleanText = rawContent.replace("\n", " ").replace(MULTI_SPACE_REGEX.toRegex(), " ").trim()
        val lower = cleanText.lowercase(Locale.ROOT)

        val prefixIdx = lower.indexOf(prefixAnchor)
        if (prefixIdx < 0) return null

        val startIdx = (prefixIdx + prefixAnchor.length).coerceAtMost(cleanText.length)
        val remainder = cleanText.substring(startIdx).trimStart()
        if (remainder.isBlank()) return null

        val candidate = if (!suffixAnchor.isNullOrBlank()) {
            val suffixIdx = remainder.lowercase(Locale.ROOT).indexOf(suffixAnchor)
            if (suffixIdx > 0) remainder.substring(0, suffixIdx) else remainder
        } else {
            remainder
        }

        val trimmed = candidate
            .split(Regex("(?:\\.|,|;|\\(|\\)|\\s+on\\s+|\\s+ref\\b|\\s+rrn\\b|\\s+utr\\b|\\s+avl\\b|\\s+bal\\b|\\s+final\\b)", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim(' ', '.', ',', ':', '-', '/', ';', '(', ')')
            ?: return null

        if (trimmed.lowercase(Locale.ROOT).startsWith("a/c") ||
            trimmed.lowercase(Locale.ROOT).startsWith("acct") ||
            trimmed.lowercase(Locale.ROOT).startsWith("account")
        ) {
            return null
        }

        return trimmed.takeIf { it.length in 2..45 && !it.all { ch -> ch.isDigit() } && !CategoryClassifier.isGenericPayee(it) }
    }

    fun getStatsSummary(): Triple<Int, Int, Int> {
        val uniqueTemplates = templates.keys.count { !it.startsWith("$ANY_SENDER||") }
        val approvedTemplates = templates.values.count { it.senderKey != ANY_SENDER && it.approvedCount > 0 }
        val blockedTemplates = templates.values.count { it.senderKey != ANY_SENDER && it.rejectedCount > it.approvedCount }
        return Triple(uniqueTemplates, approvedTemplates, blockedTemplates)
    }

    fun clearAllLearnedPatterns() {
        templates.clear()
        senders.clear()
        vpaMemory.clear()
        scheduleSave()
    }

    private fun scheduleSave() {
        val ctx = appContext ?: return
        pendingSaveJob?.cancel()
        pendingSaveJob = persistScope.launch {
            delay(350L)
            saveToDisk(ctx)
        }
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, STATE_FILE_NAME)
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONArray("templates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val senderKey = o.optString("senderKey", ANY_SENDER)
                    val skeleton = o.optString("skeleton", "")
                    if (skeleton.isBlank()) continue
                    templates[compositeKey(senderKey, skeleton)] = TemplateRecord(
                        senderKey = senderKey,
                        skeleton = skeleton,
                        approvedCount = o.optInt("approvedCount", 0),
                        rejectedCount = o.optInt("rejectedCount", 0),
                        learnedType = o.optString("learnedType", "").ifBlank { null },
                        learnedCategory = null, // Never load template-wide category overrides
                        learnedBankName = o.optString("learnedBankName", "").ifBlank { null },
                        fallbackPayee = null,
                        prefixAnchor = o.optString("prefixAnchor", "").ifBlank { null },
                        suffixAnchor = o.optString("suffixAnchor", "").ifBlank { null },
                        lastUpdated = o.optLong("lastUpdated", 0L)
                    )
                }
            }
            root.optJSONArray("senders")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val senderKey = o.optString("senderKey", "")
                    if (senderKey.isBlank()) continue
                    senders[senderKey] = SenderRecord(
                        senderKey = senderKey,
                        approvedCount = o.optInt("approvedCount", 0),
                        rejectedCount = o.optInt("rejectedCount", 0),
                        learnedBankName = o.optString("learnedBankName", "").ifBlank { null },
                        defaultPayee = null,
                        defaultCategory = null,
                        lastUpdated = o.optLong("lastUpdated", 0L)
                    )
                }
            }
            root.optJSONArray("vpas")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val vpa = o.optString("vpa", "")
                    val payee = o.optString("payee", "")
                    if (vpa.isBlank() || payee.isBlank()) continue
                    vpaMemory[vpa] = VpaRecord(
                        vpa = vpa,
                        payee = payee,
                        category = o.optString("category", "Uncategorized"),
                        usageCount = o.optInt("usageCount", 1),
                        lastUpdated = o.optLong("lastUpdated", 0L)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load learned SMS patterns, starting fresh", e)
        }
    }

    private fun saveToDisk(context: Context) {
        try {
            val root = JSONObject()

            val templateArray = JSONArray()
            templates.values
                .sortedByDescending { (it.approvedCount + it.rejectedCount) * 1000L + (it.lastUpdated / 100000L) }
                .take(MAX_TEMPLATES)
                .forEach { rec ->
                    templateArray.put(
                        JSONObject().apply {
                            put("senderKey", rec.senderKey)
                            put("skeleton", rec.skeleton)
                            put("approvedCount", rec.approvedCount)
                            put("rejectedCount", rec.rejectedCount)
                            rec.learnedType?.let { put("learnedType", it) }
                            rec.learnedBankName?.let { put("learnedBankName", it) }
                            rec.prefixAnchor?.let { put("prefixAnchor", it) }
                            rec.suffixAnchor?.let { put("suffixAnchor", it) }
                            put("lastUpdated", rec.lastUpdated)
                        }
                    )
                }
            root.put("templates", templateArray)

            val senderArray = JSONArray()
            senders.values
                .sortedByDescending { it.approvedCount + it.rejectedCount }
                .take(MAX_SENDERS)
                .forEach { sRec ->
                    senderArray.put(
                        JSONObject().apply {
                            put("senderKey", sRec.senderKey)
                            put("approvedCount", sRec.approvedCount)
                            put("rejectedCount", sRec.rejectedCount)
                            sRec.learnedBankName?.let { put("learnedBankName", it) }
                            put("lastUpdated", sRec.lastUpdated)
                        }
                    )
                }
            root.put("senders", senderArray)

            val vpaArray = JSONArray()
            vpaMemory.values
                .sortedByDescending { it.usageCount }
                .take(MAX_VPAS)
                .forEach { vRec ->
                    vpaArray.put(
                        JSONObject().apply {
                            put("vpa", vRec.vpa)
                            put("payee", vRec.payee)
                            put("category", vRec.category)
                            put("usageCount", vRec.usageCount)
                            put("lastUpdated", vRec.lastUpdated)
                        }
                    )
                }
            root.put("vpas", vpaArray)

            val target = File(context.filesDir, STATE_FILE_NAME)
            val tmp = File(context.filesDir, "$STATE_FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist learned SMS patterns", e)
        }
    }
}
