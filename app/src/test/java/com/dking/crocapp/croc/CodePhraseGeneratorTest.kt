package com.dking.crocapp.croc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePhraseGeneratorTest {

    @Test
    fun testWordListSizeAndUniqueness() {
        assertEquals("EFF Short Wordlist #1 must contain exactly 1296 words", 1296, CodePhraseGenerator.WORDS.size)
        val uniqueWords = CodePhraseGenerator.WORDS.toSet()
        assertEquals("All 1296 words must be unique", 1296, uniqueWords.size)
    }

    @Test
    fun testGeneratedCodeFormat() {
        val code = CodePhraseGenerator.generate()
        val parts = code.split("-")
        assertEquals(3, parts.size)
        parts.forEach { word ->
            assertTrue("Word '$word' must be in EFF wordlist", word in CodePhraseGenerator.WORDS)
            assertTrue("Word '$word' must be lowercase", word == word.lowercase())
            assertTrue("Word '$word' must be between 3 and 5 chars", word.length in 3..5)
        }
    }

    @Test
    fun testHighEntropyUniqueness() {
        val samples = (1..1000).map { CodePhraseGenerator.generate() }.toSet()
        // Out of 1000 generated samples from 2.17B space, all should be unique
        assertEquals(1000, samples.size)
    }
}
