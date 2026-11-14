package com.expensetracker.offline.util

/** Cleans transport markup from a captured message for display without changing the stored source. */
object OriginalMessageFormatter {
    private val accountPlaceholder = Regex("</?\\s*A/c\\s*>", RegexOption.IGNORE_CASE)
    private val lineBreakTag = Regex("<\\s*(?:br\\s*/?|/?p|/?div)\\s*>", RegexOption.IGNORE_CASE)
    private val formattingTag = Regex("</?\\s*[a-z][a-z0-9]*(?:\\s+[^<>]*)?\\s*/?\\s*>", RegexOption.IGNORE_CASE)

    fun cleanForDisplay(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(accountPlaceholder, "A/c ")
        .replace(lineBreakTag, "\n")
        .replace(formattingTag, "")
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
