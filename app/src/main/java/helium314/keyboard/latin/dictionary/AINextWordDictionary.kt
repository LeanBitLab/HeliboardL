/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.dictionary

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.AINextWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A [Dictionary] that adds LLM-driven next-word candidates supplied by an [AINextWordEngine].
 *
 * - Only produces suggestions in prediction / next-word mode, i.e. when `composedData.mTypedWord`
 *   is empty. In completion mode it returns null and never interferes with the main dictionaries.
 * - Never blocks the suggestion thread: getSuggestions() returns cached results synchronously or
 *   null, and kicks off an async fetch on its own [CoroutineScope].
 * - When the engine is not ready (pref off / model not loaded) it behaves as an empty dictionary.
 *
 * This class is pure JVM (no Android runtime types) so it can be unit-tested without instrumented
 * APIs; the result cache uses a plain LRU [LinkedHashMap] guarded by a monitor.
 */
class AINextWordDictionary(
    private val engine: AINextWordEngine,
    private val scope: CoroutineScope
) : Dictionary(Dictionary.TYPE_AI_NEXT_WORD, null) {

    // accessOrder = true -> most-recently-used entries end up at the tail, so the head is LRU.
    private val cache = LinkedHashMap<String, ArrayList<SuggestedWordInfo>>(32, 0.75f, true)

    override fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray
    ): ArrayList<SuggestedWordInfo>? {
        // Only add AI candidates in next-word mode and when the engine is actually usable.
        if (composedData.mTypedWord.isNotEmpty() || !engine.isReady()) return null

        val prompt = buildPrompt(ngramContext.extractPrevWordsContext())
        getCached(prompt)?.let { return it }

        // Trigger an async fetch; we return nothing to this pass so the suggestion thread is never
        // blocked by network / on-device inference.
        scope.launch {
            val candidates = try {
                engine.suggestNextWords(prompt)
            } catch (e: Exception) {
                emptyList()
            }
            val parsed = parseCandidates(candidates.joinToString(" "))
            if (parsed.isNotEmpty()) {
                putCached(prompt, wrapSuggestions(parsed, ngramContext.extractPrevWordsContext()))
            }
        }
        return null
    }

    override fun isInDictionary(word: String): Boolean = false

    override fun isInitialized(): Boolean = engine.isReady()

    private fun getCached(prompt: String): ArrayList<SuggestedWordInfo>? =
        synchronized(cache) { cache[prompt] }

    private fun putCached(prompt: String, value: ArrayList<SuggestedWordInfo>) {
        synchronized(cache) {
            cache[prompt] = value
            while (cache.size > MAX_CACHED_PROMPTS) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    private fun wrapSuggestions(words: List<String>, prevContext: String): ArrayList<SuggestedWordInfo> =
        ArrayList<SuggestedWordInfo>(words.size).apply {
            words.forEachIndexed { i, word ->
                add(
                    SuggestedWordInfo(
                        word,
                        prevContext,
                        BASE_SCORE + i,
                        SuggestedWordInfo.KIND_PREDICTION,
                        this@AINextWordDictionary,
                        SuggestedWordInfo.NOT_AN_INDEX,
                        SuggestedWordInfo.NOT_A_CONFIDENCE
                    )
                )
            }
        }

    companion object {
        private const val MAX_CACHED_PROMPTS = 64
        private const val BASE_SCORE = 500000
    }
}

/**
 * Builds the plain-text prompt handed to the LLM from the flat previous-words context string
 * (e.g. produced by [NgramContext.extractPrevWordsContext]). Pure function so it can be
 * unit-tested without Android.
 */
internal fun buildPrompt(context: String): String {
    val trimmed = context.trim()
    if (trimmed.isEmpty() || trimmed == BEGINNING_OF_SENTENCE_TAG) {
        return "Complete the next word of this sentence, give just one or two words."
    }
    return "Complete the next word after \"$trimmed\". Give just one or two words."
}

/**
 * Splits raw LLM output into candidate words: tokenises, trims, drops empties/duplicates and
 * caps the list at [MAX_CANDIDATES]. Pure function so it can be unit-tested without Android.
 */
internal fun parseCandidates(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val result = LinkedHashSet<String>()
    // Split on whitespace and common punctuation bound; keep letters/apostrophes/hyphens.
    for (token in raw.split(Regex("[\\s,;:!?\\.]+"))) {
        val word = token.trim().trim('\'', '"', '(', ')', '[', ']')
        if (word.isEmpty()) continue
        if (word.any { it.isLetter() }) result.add(word)
        if (result.size >= MAX_CANDIDATES) break
    }
    return result.toList()
}

private const val BEGINNING_OF_SENTENCE_TAG = "<S>"
private const val MAX_CANDIDATES = 3
