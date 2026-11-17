package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloatingLyricsWordRangesTest {
    private fun word(
        text: String,
        start: Double,
        end: Double,
    ) = WordTimestamp(text, start, end)

    @Test
    fun wordsMapToTheirSpansInOrder() {
        val ranges = floatingWordRanges("la la land", listOf(word("la", 1.0, 1.5), word("la", 1.5, 2.0), word("land", 2.0, 3.0)))
        assertEquals(
            listOf(
                FloatingWordRange(0, 2, 1000, 1500),
                FloatingWordRange(3, 5, 1500, 2000),
                FloatingWordRange(6, 10, 2000, 3000),
            ),
            ranges,
        )
    }

    @Test
    fun wordsThatDoNotSpellTheLineLightNothing() {
        assertNull(floatingWordRanges("hello world", listOf(word("hello", 0.0, 1.0), word("there", 1.0, 2.0))))
        assertNull(floatingWordRanges("hello", null))
    }
}
