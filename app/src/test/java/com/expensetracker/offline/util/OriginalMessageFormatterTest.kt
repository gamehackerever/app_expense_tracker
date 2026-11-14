package com.expensetracker.offline.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalMessageFormatterTest {
    @Test
    fun unwrapsMalformedAccountPlaceholderWithoutChangingStoredSource() {
        val source = "UPI debit Rs.185.96, A/c X5443. SMS BLK <A/c>to 9840777222-South Indian Bank"

        assertEquals(
            "UPI debit Rs.185.96, A/c X5443. SMS BLK A/c to 9840777222-South Indian Bank",
            OriginalMessageFormatter.cleanForDisplay(source)
        )
        assertEquals("UPI debit Rs.185.96, A/c X5443. SMS BLK <A/c>to 9840777222-South Indian Bank", source)
    }

    @Test
    fun removesFormattingTagsAndDecodesCommonEntities() {
        assertEquals(
            "Paid & received\nA/c XX1234",
            OriginalMessageFormatter.cleanForDisplay("<b>Paid &amp; received</b><br/>A/c XX1234")
        )
        assertEquals(
            "SMS BLK A/c to 9840777222",
            OriginalMessageFormatter.cleanForDisplay("SMS BLK &lt;A/c&gt;to 9840777222")
        )
    }
}
