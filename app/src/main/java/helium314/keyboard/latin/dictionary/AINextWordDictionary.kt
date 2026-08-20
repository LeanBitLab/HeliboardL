/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.dictionary

import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.AINextWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AINextWord"

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

    /**
     * Invoked on the [scope] dispatcher after the async fetch has filled the cache with new
     * LLM candidates. The suggestion strip must re-run getSuggestions() for these to surface,
     * so whoever owns this dictionary should wire this to a strip refresh (postUpdateSuggestionStrip).
     */
    @Volatile
    var onCandidatesReady: (() -> Unit)? = null

    /**
     * Latest bounded text before the cursor (set by the IME before each next-word fetch via the
     * DictionaryFacilitator). Gives the model the actual sentence/message context instead of only
     * the last few n-gram words. Read synchronously in getSuggestions() to build the prompt.
     */
    @Volatile
    var prevTextForPrompt: String? = null

    /** Current app package name — key for the persisted per-app context buffer. */
    @Volatile
    var appPackageName: String? = null

    /** The app's preferred keyboard language code (e.g. "en", "pl") to keep the model consistent. */
    @Volatile
    var appLanguageHint: String? = null

    /** True when the current field is private/incognito — suppresses per-app context use. */
    @Volatile
    var noLearning: Boolean = false

    /** Persisted recent text from this app (survives restarts); null when private. */
    @Volatile
    var appContext: String? = null

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
        if (composedData.mTypedWord.isNotEmpty() || !engine.isReady()) {
            Log.d(TAG, "getSuggestions SKIP typedWord='${composedData.mTypedWord}' engineReady=${engine.isReady()}")
            return null
        }

        // Prefer the real text-before-cursor (whole sentence/message context) when available;
        // fall back to the local n-gram window otherwise. Prepend any persisted per-app context
        // when present and the field is not private.
        val base = prevTextForPrompt?.takeIf { it.isNotBlank() }
            ?: ngramContext.extractPrevWordsContext()
        val fullContext = if (!noLearning && !appContext.isNullOrBlank()) "${appContext!!.trim()} $base" else base
        // If the text before the cursor already ends a sentence (".", "!", "?"), the next word
        // starts a brand-new sentence -> capitalise every AI continuation so it displays and
        // inserts correctly at the start of a sentence.
        val capitaliseNext = endsSentence(fullContext)
        val prompt = buildPrompt(fullContext, appLanguageHint)
        getCached(prompt)?.let {
            Log.d(TAG, "getSuggestions CACHE-HIT prompt='$prompt' -> ${it.size} words")
            return it
        }
        Log.d(TAG, "getSuggestions CACHE-MISS, launching async fetch prompt='$prompt'")

        // Tokenise the context so we can drop candidate repeats (issue 2): never suggest a word
        // that already appears in the current sentence/context (e.g. type "dog" -> don't re-suggest "dog").
        val contextWordSet = wordTokenSet(fullContext)

        // Trigger an async fetch; we return nothing to this pass so the suggestion thread is never
        // blocked by network / on-device inference.
        scope.launch {
            val candidates = try {
                engine.suggestNextWords(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "suggestNextWords threw", e)
                emptyList()
            }
            val parsed = parseCandidates(candidates.joinToString(" "))
                .filterNot { contextWordSet.contains(it.lowercase()) }
            Log.d(TAG, "async fetch done: engineCandidates=$candidates parsed=$parsed (repeats filtered from context words)")
            // Capitalise each continuation at the start of a sentence (after ".", "!", "?").
            val toShow = if (capitaliseNext)
                parsed.map { it.replaceFirstChar { c -> c.uppercase() } }
            else parsed
            if (toShow.isNotEmpty()) {
                putCached(prompt, wrapSuggestions(toShow, ngramContext.extractPrevWordsContext()))
                // Notify the owner that fresh candidates are available so the suggestion strip
                // can re-run getSuggestions() and surface them without another keystroke.
                onCandidatesReady?.invoke()
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
        // Base score for AI next-word candidates. Kept modest so AI words offer EXTRA choices that
        // rank BELOW good AOSP next-word suggestions, instead of barging to the front and displacing
        // the suggestion the user is about to tap. (SuggestionResults is a TreeSet sorted by score.)
        private const val BASE_SCORE = 1000
    }
}

/**
 * True when [text] already ends with an end-of-sentence mark (".", "!", "?") after trimming —
 * i.e. the very next word starts a new sentence and should be capitalised. Pure function so it can
 * be unit-tested without Android.
 */
internal fun endsSentence(text: String): Boolean =
    text.trim().let { it.isNotEmpty() && it.last() in ".!?" }

/**
 * Builds the plain-text prompt handed to the LLM from the text before the cursor (or the flat
 * previous-words context from [NgramContext.extractPrevWordsContext] as a fallback). Pure
 * function so it can be unit-tested without Android. Deliberately directive so a small instruct
 * model replies with ONE natural continuation word rather than a whole sentence.
 */
internal fun buildPrompt(context: String, language: String? = null): String {
    val trimmed = context.trim()
    // Keep the model consistent with the app's keyboard language, but only when it's a real
    // language hint (not the default "en").
    val langLine = if (!language.isNullOrBlank() && !language.equals("en", ignoreCase = true)) " Continue in $language." else ""
    if (trimmed.isEmpty() || trimmed == BEGINNING_OF_SENTENCE_TAG) {
        return "I am starting a new message. Output only a single likely next word.$langLine"
    }
    return "Continue this text with exactly one natural next word, outputting only that one word:$langLine\n\"$trimmed\"\nNext word:"
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

/** Tokenises [text] into its lowercase word set (letters/digits/apostrophes), used to drop
 * candidate repeats that already appear in the context. Pure function for unit testing. */
internal fun wordTokenSet(text: String): Set<String> {
    val out = HashSet<String>()
    for (token in text.split(Regex("[^\\p{L}\\p{N}']+"))) {
        val w = token.trim().lowercase()
        if (w.isNotEmpty()) out.add(w)
    }
    return out
}
