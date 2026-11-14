package com.expensetracker.offline.util

/** "1 item" / "2 items". Pass [plural] for irregular words ("category" -> "categories"). */
fun pluralize(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
