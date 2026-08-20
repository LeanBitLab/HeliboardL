/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.dictionary

import android.text.TextUtils
import android.util.Log
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.AINextWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import org.mockito.junit.MockitoJUnitRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(MockitoJUnitRunner::class)
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
        assertTrue(buildPrompt("").contains("word"))
        assertTrue(buildPrompt("<S>").contains("word"))
    }

    @Test
    fun buildPrompt_withWords_isDirectiveSingleWord() {
        val prompt = buildPrompt("I took the dog for a")
        assertTrue(prompt.contains("I took the dog for a"))
        assertTrue(prompt.contains("one")) // "exactly one natural next word"
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

    @Test
    fun wordTokenSet_tokenizesToLowercaseWords() {
        assertEquals(
            setOf("i", "took", "the", "dog", "for", "a", "big"),
            wordTokenSet("I took the dog for a — big!")
        )
        assertFalse(wordTokenSet("hello world").contains("dog"))
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

    // ---------- refresh signal ----------

    @Test
    fun getSuggestions_inPredictionMode_firesOnCandidatesReadyWhenCandidatesCached() {
        val callbacks = AtomicInteger(0)
        val dict = newDictionary()
        dict.onCandidatesReady = { callbacks.incrementAndGet() }

        // Mock android Log + TextUtils, both unmocked statics in plain JVM tests.
        Mockito.mockStatic(Log::class.java).use { _ ->
            Mockito.mockStatic(TextUtils::class.java).use { textUtils ->
                textUtils.`when`<String> {
                    TextUtils.join(
                        Mockito.any<CharSequence>(),
                        Mockito.anyList<ArrayList<String>>()
                    )
                }.thenReturn("the quick")

                // Prediction mode: empty typed word + a real previous-words context.
                val composed = ComposedData(InputPointers(4), false /* isBatchMode */, "" /* mTypedWord */)
                val ngram = NgramContext(NgramContext.WordInfo("the"), NgramContext.WordInfo("quick"))
                val settings = SettingsValuesForSuggestion(false, false, "TOUCH")

                val results = dict.getSuggestions(
                    composed, ngram, 0L /* proximityInfoHandle */, settings,
                    0 /* sessionId */, 1.0f /* weightForLocale */, floatArrayOf(0.5f, 0.5f)
                )

                // First pass is a cache miss (returns null) but kicks off the async fetch, which caches
                // the fake engine's candidates and must have fired the refresh callback.
                assertTrue(results == null || results.isEmpty())
            }
        }
        // With Dispatchers.Unconfined the fetch completes synchronously, so the callback fired.
        assertEquals(1, callbacks.get())
    }

    // ---------- sentence-start capitalisation ----------

    @Test
    fun endsSentence_afterFullStop_isTrue() {
        assertTrue(endsSentence("Take the dog for a walk."))
        assertTrue(endsSentence("Why?\n"))
        assertTrue(endsSentence("Wow!  "))
    }

    @Test
    fun endsSentence_midSentence_isFalse() {
        assertFalse(endsSentence("Take the dog for a"))
        assertFalse(endsSentence("take the dog for a walk"))
        assertFalse(endsSentence(""))
        assertFalse(endsSentence("   "))
    }
}
