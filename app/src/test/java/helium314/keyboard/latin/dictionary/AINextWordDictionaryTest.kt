/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.dictionary

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.utils.AINextWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AINextWordDictionaryTest {

    private fun fakeEngine(): AINextWordEngine = object : AINextWordEngine {
        override fun isReady(): Boolean = true
        override suspend fun suggestNextWords(prompt: String): List<String> =
            listOf("world", "friend")
    }

    private fun newDictionary() =
        AINextWordDictionary(fakeEngine(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    // ---------- buildPrompt ----------

    @Test
    fun buildPrompt_emptyContext_hasGenericInstruction() {
        assertTrue(buildPrompt("").isNotBlank())
        assertTrue(buildPrompt("").contains("Complete"))
        assertTrue(buildPrompt("<S>").contains("Complete"))
    }

    @Test
    fun buildPrompt_withWords_embedsTheWords() {
        val prompt = buildPrompt("the quick")
        assertTrue(prompt.contains("the"))
        assertTrue(prompt.contains("quick"))
    }

    // ---------- parseCandidates ----------

    @Test
    fun parseCandidates_splitsAndCleans() {
        val result = parseCandidates("hello world, again. \"friend\"")
        assertEquals(listOf("hello", "world", "again"), result)
    }

    @Test
    fun parseCandidates_emptyInput_returnsEmpty() {
        assertTrue(parseCandidates("").isEmpty())
        assertTrue(parseCandidates("   ").isEmpty())
    }

    @Test
    fun parseCandidates_dedupes() {
        val result = parseCandidates("the the the the")
        assertEquals(1, result.size)
        assertEquals("the", result[0])
    }

    @Test
    fun parseCandidates_capsAtThree() {
        val result = parseCandidates("one two three four five")
        assertEquals(3, result.size)
        assertEquals(listOf("one", "two", "three"), result)
    }

    @Test
    fun parseCandidates_dropsNonLetterTokens() {
        val result = parseCandidates("123 !!! ...")
        assertTrue(result.isEmpty())
    }

    // ---------- sanity ----------

    @Test
    fun dictionary_isNotReadyWhenEngineNotReady() {
        val engine = object : AINextWordEngine {
            override fun isReady(): Boolean = false
            override suspend fun suggestNextWords(prompt: String): List<String> = emptyList()
        }
        val dict = AINextWordDictionary(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(dict.isInitialized())
        assertFalse(dict.isInDictionary("hello"))
    }
}
