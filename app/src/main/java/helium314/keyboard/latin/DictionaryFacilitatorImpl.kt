/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import android.util.LruCache
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.DictionaryFacilitator.DictionaryInitializationListener
import helium314.keyboard.latin.NgramContext.WordInfo
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.common.decapitalize
import helium314.keyboard.latin.common.mightBeEmoji
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.AINextWordDictionary
import helium314.keyboard.latin.dictionary.AppsBinaryDictionary
import helium314.keyboard.latin.dictionary.ContactsBinaryDictionary
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.dictionary.DictionaryStats
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.dictionary.UserBinaryDictionary
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.personalization.SessionWordBoost
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.AINextWordEngineFactory
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Facilitates interaction with different kinds of dictionaries. Provides APIs
 * to instantiate and select the correct dictionaries (based on language and settings),
 * update entries and fetch suggestions.
 *
 *
 * Currently AndroidSpellCheckerService and LatinIME both use DictionaryFacilitator as
 * a client for interacting with dictionaries.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictionaryFacilitatorImpl : DictionaryFacilitator {
    private var mPrefs: SharedPreferences? = null
    private var mContext: Context? = null
    private var mEnabledDictionariesState: Map<String, Boolean> = emptyMap()
    private var mLoadedDownloadPrefs: Map<String, Any?> = emptyMap()
    private var mLoadedAINextWord: Boolean? = null
    private var dictionaryGroups = listOf(DictionaryGroup())

    @Volatile
    private var mLatchForWaitingLoadingMainDictionaries = CountDownLatch(0)

    // The library does not deal well with ngram history for auto-capitalized words, so we adjust
    // the ngram context to store next word suggestions for such cases.
    // todo: this is awful, find a better solution / workaround
    //  or remove completely? not sure if it's actually an improvement
    //  should be fixed in the library, but that's not feasible with current user-provides-library approach
    //  added in 12cbd43bda7d0f0cd73925e9cf836de751c32ed0 / https://github.com/Helium314/HeliBoard/issues/135
    private var tryChangingWords = false
    private var changeFrom = ""
    private var changeTo = ""

    private var mLoadedSuggestEmojis: Boolean = false
    private var mLoadedEmojiDictExists: Boolean = false

    private val SPELLING_DICTIONARY_TYPES = arrayOf(
        Dictionary.TYPE_MAIN,
        Dictionary.TYPE_CONTACTS,
        Dictionary.TYPE_APPS,
        Dictionary.TYPE_USER,
        Dictionary.TYPE_USER_HISTORY
    )

    // Caches for spell checking word validity
    private var mValidSpellingWordReadCache: LruCache<String, Boolean>? = LruCache(500)
    private var mValidSpellingWordWriteCache: LruCache<String, Boolean>? = LruCache(500)

    // Limit parallelism to prevent excessive CPU usage during dictionary operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    // Optional AI next-word dictionary (LeanType). Null when the feature is off or the engine
    // cannot be built (factory gates on the pref / model availability).
    private var aiNextWordDict: AINextWordDictionary? = null

    // Runnable that LatinIME registers so we can ask it to redraw the suggestion strip once the
    // AI next-word dictionary has cached fresh candidates (see setNextWordRefreshListener).
    private var nextWordRefreshListener: (() -> Unit)? = null

    override fun setValidSpellingWordReadCache(cache: LruCache<String, Boolean>) {
        mValidSpellingWordReadCache = cache
    }

    override fun setValidSpellingWordWriteCache(cache: LruCache<String, Boolean>) {
        mValidSpellingWordWriteCache = cache
    }

    // judging by usage before adding multilingual typing, this should check primary group locale only
    override fun isForLocale(locale: Locale?): Boolean {
        return locale != null && locale == dictionaryGroups[0].locale
    }

    override fun onStartInput() {
    }

    override fun onFinishInput() {
        for (dictGroup in dictionaryGroups) {
            DictionaryFacilitator.ALL_DICTIONARY_TYPES.forEach { dictGroup.getDict(it)?.onFinishInput() }
        }
        // Persist session word boost data on input finish
        sessionWordBoost?.flushIfDirty()
    }

    private var sessionWordBoost: SessionWordBoost? = null

    /** Lazily initialize SessionWordBoost when context is available */
    private fun getSessionWordBoost(context: Context): SessionWordBoost {
        return sessionWordBoost ?: SessionWordBoost.getInstance(context).also { sessionWordBoost = it }
    }

    override fun isActive(): Boolean {
        return dictionaryGroups[0].locale.language.isNotEmpty()
    }

    override fun getMainLocale(): Locale {
        return dictionaryGroups[0].locale
    }

    override fun getCurrentLocale(): Locale {
        return currentlyPreferredDictionaryGroup.locale
    }

    override fun usesSameSettings(locales: List<Locale>, contacts: Boolean, apps: Boolean, personalization: Boolean): Boolean {
        val prefs = mPrefs
        if (prefs != null) {
            val currentPrefs = prefs.all.filterKeys { it.startsWith("pref_dict_enabled_") }
                .mapValues { it.value as? Boolean ?: true }
            val currentDownloadPrefs = prefs.all.filterKeys { it.startsWith("pref_dict_download_link_") }
            if (currentPrefs != mEnabledDictionariesState || currentDownloadPrefs != mLoadedDownloadPrefs) {
                return false
            }
            val aiNextWord = prefs.getBoolean(Settings.PREF_AI_NEXT_WORD, false)
            if (aiNextWord != mLoadedAINextWord) {
                return false
            }
        }
        val ctx = mContext ?: return false
        val currentSuggestEmojis = Settings.getValues().mSuggestEmojis
        val currentEmojiDictExists = locales.any { helium314.keyboard.latin.utils.DictionaryInfoUtils.getCachedDictForLocaleAndType(it, Dictionary.TYPE_EMOJI, ctx) != null }
        if (currentSuggestEmojis != mLoadedSuggestEmojis || currentEmojiDictExists != mLoadedEmojiDictExists) {
            return false
        }
        val dictGroup = dictionaryGroups[0] // settings are the same for all groups
        return contacts == dictGroup.hasDict(Dictionary.TYPE_CONTACTS)
                && apps == dictGroup.hasDict(Dictionary.TYPE_APPS)
                && personalization == dictGroup.hasDict(Dictionary.TYPE_USER_HISTORY)
                && locales.size == dictionaryGroups.size
                && locales.none { findDictionaryGroupWithLocale(dictionaryGroups, it) == null }
    }

    // -------------- managing (loading & closing) dictionaries ------------

    override fun resetDictionaries(
        context: Context,
        newLocale: Locale,
        useContactsDict: Boolean,
        useAppsDict: Boolean,
        usePersonalizedDicts: Boolean,
        forceReloadMainDictionary: Boolean,
        dictNamePrefix: String,
        listener: DictionaryInitializationListener?
    ) {
        Log.i(TAG, "resetDictionaries, force reloading main dictionary: $forceReloadMainDictionary")
        mContext = context.applicationContext
        val prefs = context.prefs()
        mPrefs = prefs
        mEnabledDictionariesState = prefs.all.filterKeys { it.startsWith("pref_dict_enabled_") }
            .mapValues { it.value as? Boolean ?: true }
        mLoadedDownloadPrefs = prefs.all.filterKeys { it.startsWith("pref_dict_download_link_") }

        // Track the AI next-word pref so the dictionary group is rebuilt when it is toggled.
        mLoadedAINextWord = prefs.getBoolean(Settings.PREF_AI_NEXT_WORD, false)

        // Initialize session word boost with context if not yet done
        if (sessionWordBoost == null) {
            sessionWordBoost = SessionWordBoost.getInstance(context)
        }

        val locales = getUsedLocales(newLocale, context)

        val subDictTypesToUse = listOfNotNull(
            Dictionary.TYPE_USER,
            if (useAppsDict) Dictionary.TYPE_APPS else null,
            if (usePersonalizedDicts) Dictionary.TYPE_USER_HISTORY else null,
            if (useContactsDict && PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.READ_CONTACTS))
                Dictionary.TYPE_CONTACTS else null
        )

        val (newDictionaryGroups, existingDictsToCleanup) =
            getNewDictGroupsAndDictsToCleanup(locales, subDictTypesToUse, forceReloadMainDictionary, dictNamePrefix, context)

        // Replace Dictionaries.
        val oldDictionaryGroups: List<DictionaryGroup>
        synchronized(this) {
            oldDictionaryGroups = dictionaryGroups
            dictionaryGroups = newDictionaryGroups
            if (hasAtLeastOneUninitializedMainDictionary()) {
                asyncReloadUninitializedMainDictionaries(context, locales, listener)
            }
        }

        listener?.onUpdateMainDictionaryAvailability(hasAtLeastOneInitializedMainDictionary())

        // Clean up old dictionaries in the background to avoid blocking the main thread.
        scope.launch {
            existingDictsToCleanup.forEach { (locale, dictTypes) ->
                val dictGroupToCleanup = findDictionaryGroupWithLocale(oldDictionaryGroups, locale) ?: return@forEach
                for (dictType in dictTypes) {
                    dictGroupToCleanup.closeDict(dictType)
                }
            }
        }

        mValidSpellingWordWriteCache?.evictAll()
        mValidSpellingWordReadCache?.evictAll()

        // AI next-word dictionary: rebuilt on every dictionary reset. Factory returns null when
        // the feature is disabled or the engine cannot be built, making this a no-op by default.
        aiNextWordDict = AINextWordEngineFactory.create(context)?.let {
            AINextWordDictionary(it, scope).also { dict ->
                // Fresh LLM candidates cached -> ask LatinIME to redraw the suggestion strip so
                // they surface without another keystroke.
                dict.onCandidatesReady = { nextWordRefreshListener?.invoke() }
            }
        }
        Log.i(TAG, "resetDictionaries: AINextWord dict ${if (aiNextWordDict == null) "NOT created" else "created"} (pref=${prefs.getBoolean(Settings.PREF_AI_NEXT_WORD, false)})")
    }

    override fun setNextWordRefreshListener(listener: Runnable?) {
        nextWordRefreshListener = listener?.let { { it.run() } }
    }

    @Volatile
    private var aiNextWordContextText: String? = null

    override fun setAINextWordContextText(text: String?) {
        aiNextWordContextText = text
    }

    @Volatile private var aiNextWordAppPackage: String? = null
    @Volatile private var aiNextWordAppLanguage: String? = null
    @Volatile private var aiNextWordNoLearning: Boolean = false
    @Volatile private var aiNextWordPersistedContext: String? = null

    override fun setAINextWordAppInfo(
        packageName: String?, language: String?, noLearning: Boolean, persistedContext: String?
    ) {
        aiNextWordAppPackage = packageName
        aiNextWordAppLanguage = language
        aiNextWordNoLearning = noLearning
        aiNextWordPersistedContext = persistedContext
    }

    /** creates dictionaryGroups for [newLocales] with given [newSubDictTypes], trying to re-use existing dictionaries.
     * returns the new dictionaryGroups and unused dictionary types by locale */
    private fun getNewDictGroupsAndDictsToCleanup(
        newLocales: Collection<Locale>,
        newSubDictTypes: Collection<String>,
        forceReload: Boolean,
        dictNamePrefix: String,
        context: Context
    ): Pair<List<DictionaryGroup>, Map<Locale, List<String>>> {
        // Gather all dictionaries by locale. We may remove some from the list later.
        val existingDictsToCleanup = HashMap<Locale, MutableList<String>>()
        for (dictGroup in dictionaryGroups) {
            existingDictsToCleanup[dictGroup.locale] = DictionaryFacilitator.ALL_DICTIONARY_TYPES
                .filterTo(mutableListOf()) { dictGroup.hasDict(it) }
        }

        // create new dictionary groups and remove dictionaries to re-use from existingDictsToCleanup
        val newDictionaryGroups = mutableListOf<DictionaryGroup>()
        for (locale in newLocales) {
            // get existing dictionary group for new locale
            val oldDictGroupForLocale = findDictionaryGroupWithLocale(dictionaryGroups, locale)
            val dictTypesToCleanupForLocale = existingDictsToCleanup[locale]

            // create new or re-use already loaded main dict
            val mainDict: Dictionary?
            val currentSuggestEmojis = Settings.getValues().mSuggestEmojis
            val currentEmojiDictExists = helium314.keyboard.latin.utils.DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context) != null
            val forceReloadMain = forceReload || (currentSuggestEmojis != mLoadedSuggestEmojis) || (currentEmojiDictExists != mLoadedEmojiDictExists)

            if (forceReloadMain || oldDictGroupForLocale == null
                || !oldDictGroupForLocale.hasDict(Dictionary.TYPE_MAIN)
            ) {
                mainDict = null // null main dicts will be loaded later in asyncReloadUninitializedMainDictionaries
            } else {
                mainDict = oldDictGroupForLocale.getDict(Dictionary.TYPE_MAIN)
                dictTypesToCleanupForLocale?.remove(Dictionary.TYPE_MAIN)
            }

            // create new or re-use already loaded sub-dicts
            val subDicts: MutableMap<String, ExpandableBinaryDictionary> = HashMap()
            for (subDictType in newSubDictTypes) {
                val subDict: ExpandableBinaryDictionary
                if (forceReload || oldDictGroupForLocale == null
                    || !oldDictGroupForLocale.hasDict(subDictType)
                ) {
                    // Create a new dictionary.
                    subDict = createSubDict(subDictType, context, locale, null, dictNamePrefix) ?: continue
                } else {
                    // Reuse the existing dictionary.
                    subDict = oldDictGroupForLocale.getSubDict(subDictType) ?: continue
                    dictTypesToCleanupForLocale?.remove(subDictType)
                }
                subDicts[subDictType] = subDict
            }
            val newDictGroup = DictionaryGroup(locale, mainDict, subDicts, context)
            newDictionaryGroups.add(newDictGroup)
        }
        return newDictionaryGroups to existingDictsToCleanup
    }

    private fun asyncReloadUninitializedMainDictionaries(
        context: Context, locales: Collection<Locale>, listener: DictionaryInitializationListener?
    ) {
        val latchForWaitingLoadingMainDictionary = CountDownLatch(1)
        mLatchForWaitingLoadingMainDictionaries = latchForWaitingLoadingMainDictionary
        scope.launch {
            try {
                val useEmojiDict = Settings.getValues().mSuggestEmojis
                mLoadedSuggestEmojis = useEmojiDict
                mLoadedEmojiDictExists = locales.any { helium314.keyboard.latin.utils.DictionaryInfoUtils.getCachedDictForLocaleAndType(it, Dictionary.TYPE_EMOJI, context) != null }
                val dictGroupsWithNewMainDict = locales.mapNotNull {
                    val dictionaryGroup = findDictionaryGroupWithLocale(dictionaryGroups, it)
                    if (dictionaryGroup == null) {
                        Log.w(TAG, "Expected a dictionary group for $it but none found")
                        return@mapNotNull null // This should never happen
                    }
                    if (dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.isInitialized == true) null
                    else dictionaryGroup to DictionaryFactory.createMainDictionaryCollection(context, it, useEmojiDict)
                }
                synchronized(this) {
                    dictGroupsWithNewMainDict.forEach { (dictGroup, mainDict) ->
                        dictGroup.setMainDict(mainDict)
                    }
                }

                listener?.onUpdateMainDictionaryAvailability(hasAtLeastOneInitializedMainDictionary())
            } catch (e: Throwable) {
                Log.e(TAG, "could not initialize main dictionaries for $locales", e)
            } finally {
                latchForWaitingLoadingMainDictionary.countDown()
            }
        }
    }

    override fun closeDictionaries() {
        onFinishInput() // the dictionaries will save updates to file
        val dictionaryGroupsToClose: List<DictionaryGroup>
        synchronized(this) {
            dictionaryGroupsToClose = dictionaryGroups
            dictionaryGroups = listOf(DictionaryGroup())
        }
        for (dictionaryGroup in dictionaryGroupsToClose) {
            for (dictType in DictionaryFacilitator.ALL_DICTIONARY_TYPES) {
                dictionaryGroup.closeDict(dictType)
            }
        }
    }

    // The main dictionaries are loaded asynchronously. Don't cache the return value of these methods.
    override fun hasAtLeastOneInitializedMainDictionary(): Boolean =
        dictionaryGroups.any { it.getDict(Dictionary.TYPE_MAIN)?.isInitialized == true }

    override fun hasAtLeastOneUninitializedMainDictionary(): Boolean =
        dictionaryGroups.any { it.getDict(Dictionary.TYPE_MAIN)?.isInitialized != true }

    @Throws(InterruptedException::class)
    override fun waitForLoadingMainDictionaries(timeout: Long, unit: TimeUnit) {
        mLatchForWaitingLoadingMainDictionaries.await(timeout, unit)
    }

    // -------------- actual dictionary stuff like getting suggestions ------------

    override fun addToUserHistory(
        suggestion: String, wasAutoCapitalized: Boolean, ngramContext: NgramContext,
        timeStampInSeconds: Long, blockPotentiallyOffensive: Boolean
    ) {
        // Update the spelling cache before learning. Words that are not yet added to user history
        // and appear in no other language model are not considered valid.
        putWordIntoValidSpellingWordCache("addToUserHistory", suggestion)

        // Record in session boost for personalized ranking across restarts
        sessionWordBoost?.recordWord(suggestion)

        val words = suggestion.splitOnWhitespace().dropLastWhile { it.isEmpty() }

        // increase / decrease confidence
        if (words.size == 1) // ignore if more than a single word, which only happens with (badly working) spaceAwareGesture
            adjustConfidences(suggestion, wasAutoCapitalized)

        // Add word to user dictionary if it is in no other dictionary except user history dictionary (i.e. typed again).
        val sv = Settings.getValues()
        if (sv.mAddToPersonalDictionary // require the opt-in
            && sv.mAutoCorrectEnabled == sv.mAutoCorrectionEnabledPerUserSettings // don't add if user wants autocorrect but input field does not, see https://github.com/Helium314/HeliBoard/issues/427#issuecomment-1905438000
            && dictionaryGroups[0].hasDict(Dictionary.TYPE_USER_HISTORY) // require personalized suggestions
            && !wasAutoCapitalized // we can't be 100% sure about what the user intended to type, so better don't add it
            && words.size == 1 // only single words
        ) {
            addToPersonalDictionaryIfInvalidButInHistory(suggestion)
        }

        var ngramContextForCurrentWord = ngramContext
        val preferredGroup = currentlyPreferredDictionaryGroup
        for (i in words.indices) {
            val currentWord = words[i]
            val wasCurrentWordAutoCapitalized = (i == 0) && wasAutoCapitalized
            // add to history for preferred dictionary group, to avoid mixing languages in history
            addWordToUserHistory(
                preferredGroup, ngramContextForCurrentWord, currentWord,
                wasCurrentWordAutoCapitalized, timeStampInSeconds.toInt(), blockPotentiallyOffensive
            )
            ngramContextForCurrentWord = ngramContextForCurrentWord.getNextNgramContext(WordInfo(currentWord))

            // ponytail: do not automatically remove blacklisted words from blacklist on type
            /*
            dictionaryGroups.filter { it.confidence == preferredGroup.confidence }.forEach {
                it.removeFromBlacklist(currentWord)
            }
            */
        }
    }

    private fun addWordToUserHistory(
        dictionaryGroup: DictionaryGroup, ngramContext: NgramContext, word: String, wasAutoCapitalized: Boolean,
        timeStampInSeconds: Int, blockPotentiallyOffensive: Boolean
    ) {
        if (dictionaryGroup.isBlacklisted(word)) return
        val userHistoryDictionary = dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY) ?: return

        val mainFreq = dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.getFrequency(word) ?: Dictionary.NOT_A_PROBABILITY
        if (mainFreq == 0 && blockPotentiallyOffensive)
            return
        if (tryChangingWords)
            tryChangingWords = ngramContext.changeWordIfAfterBeginningOfSentence(changeFrom, changeTo)

        val wordToUse: String
        // Check for isBeginningOfSentenceContext too, because not all text fields auto-capitalize in this case.
        // Even if the user capitalizes manually, they most likely don't want the capitalized form suggested.
        if (wasAutoCapitalized || ngramContext.isBeginningOfSentenceContext) {
            val decapitalizedWord = word.decapitalize(dictionaryGroup.locale) // try undoing auto-capitalization
            if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup)
                && !isValidWord(decapitalizedWord, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup)
            ) {
                // If the word was auto-capitalized and exists only as a capitalized word in the
                // dictionary, then we must not downcase it before registering it. For example,
                // the name of the contacts in start-of-sentence position would come here with the
                // wasAutoCapitalized flag: if we downcase it, we'd register a lower-case version
                // of that contact's name which would end up popping in suggestions.
                wordToUse = word
            } else {
                // If however the word is not in the dictionary, or exists as a de-capitalized word
                // only, then we consider that was a lower-case word that had been auto-capitalized.
                wordToUse = decapitalizedWord
                tryChangingWords = true
                changeFrom = word
                changeTo = wordToUse
            }
        } else {
            // HACK: We'd like to avoid adding the capitalized form of common words to the User
            // History dictionary in order to avoid suggesting them until the dictionary
            // consolidation is done.
            // TODO: Remove this hack when ready.
            val lowerCasedWord = word.lowercase(dictionaryGroup.locale)
            val lowerCaseFreqInMainDict = dictionaryGroup.getDict(Dictionary.TYPE_MAIN)?.getFrequency(lowerCasedWord)
                ?: Dictionary.NOT_A_PROBABILITY
            wordToUse = if (mainFreq < lowerCaseFreqInMainDict
                && lowerCaseFreqInMainDict >= CAPITALIZED_FORM_MAX_PROBABILITY_FOR_INSERT
            ) {
                // Use lower cased word as the word can be a distracter of the popular word.
                lowerCasedWord
            } else {
                word
            }
        }
        // We demote unrecognized words (frequency <= 0) by specifying them as "invalid".
        // We don't add words with 0-frequency (assuming they would be profanity etc.).
        val isValid = mainFreq > 0
        UserHistoryDictionary.addToDictionary(userHistoryDictionary, ngramContext, wordToUse, isValid, timeStampInSeconds)
    }

    private fun addToPersonalDictionaryIfInvalidButInHistory(word: String) {
        if (word.length <= 1) return
        val dictionaryGroup = clearlyPreferredDictionaryGroup ?: return
        val userDict = dictionaryGroup.getSubDict(Dictionary.TYPE_USER) ?: return
        val userHistoryDict = dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY) ?: return
        if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup))
            return // valid word, no reason to auto-add it to personal dict
        if (userDict.isInDictionary(word))
            return // should never happen, but better be safe

        // User history always reports words as invalid, so we check the frequency instead.
        // Testing shows that after 2 times adding, the frequency is 111, and then rises slowly with usage (values vary slightly).
        // 120 is after 3 uses of the word, so we simply require more than that. todo: Could be made configurable.
        // Words added to dictionaries (user and history) seem to be found only after some delay.
        // This is not too bad, but it delays adding in case a user wants to fill a dictionary using this functionality
        if (userHistoryDict.getFrequency(word) > 120) {
            scope.launch {
                // adding can throw IllegalArgumentException: Unknown URL content://user_dictionary/words
                // https://stackoverflow.com/q/41474623 https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/490
                // apparently some devices don't have a dictionary? or it's just sporadic hiccups?
                runCatching { UserDictionary.Words.addWord(userDict.mContext, word, 250, null, dictionaryGroup.locale) }
            }
        }
    }

    private fun putWordIntoValidSpellingWordCache(caller: String, originalWord: String) {
        if (mValidSpellingWordWriteCache == null)
            return

        val lowerCaseWord = originalWord.lowercase(currentLocale)
        val lowerCaseValid = isValidSpellingWord(lowerCaseWord)
        mValidSpellingWordWriteCache?.put(lowerCaseWord, lowerCaseValid)

        val capitalWord = StringUtils.capitalizeFirstAndDowncaseRest(originalWord, currentLocale)
        val capitalValid = if (lowerCaseValid) {
            true // The lower case form of the word is valid, so the upper case must be valid.
        } else {
            isValidSpellingWord(capitalWord)
        }
        mValidSpellingWordWriteCache?.put(capitalWord, capitalValid)
    }

    override fun adjustConfidences(word: String, wasAutoCapitalized: Boolean) {
        if (dictionaryGroups.size == 1 || word.contains(Constants.WORD_SEPARATOR))
            return

        // if suggestion was auto-capitalized, check against both the suggestion and the de-capitalized suggestion
        val decapitalizedSuggestion = if (wasAutoCapitalized) word.decapitalize(currentLocale) else word
        dictionaryGroups.forEach {
            if (isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it)) {
                it.increaseConfidence()
                return@forEach
            }
            // also increase confidence if suggestion was auto-capitalized and the lowercase variant it valid
            if (wasAutoCapitalized && isValidWord(decapitalizedSuggestion, DictionaryFacilitator.ALL_DICTIONARY_TYPES, it))
                it.increaseConfidence()
            else it.decreaseConfidence()
        }
    }

    /** the dictionaryGroup with most confidence, first group when tied  */
    private val currentlyPreferredDictionaryGroup: DictionaryGroup get() = dictionaryGroups.maxBy { it.confidence }

    /** the only dictionary group, or the dictionaryGroup confidence >= DictionaryGroup.MAX_CONFIDENCE if all others have 0 */
    private val clearlyPreferredDictionaryGroup: DictionaryGroup? get() {
        if (dictionaryGroups.size == 1) return dictionaryGroups.first() // confidence not used if we only have a single group

        val preferred = currentlyPreferredDictionaryGroup
        if (preferred.confidence < DictionaryGroup.MAX_CONFIDENCE) return null
        if (dictionaryGroups.any { it.confidence > 0 && it !== preferred })
            return null
        return preferred
    }

    override fun unlearnFromUserHistory(word: String, ngramContext: NgramContext, timeStampInSeconds: Long, eventType: Int) {
        // TODO: Decide whether or not to remove the word on EVENT_BACKSPACE.
        if (eventType != Constants.EVENT_BACKSPACE) {
            currentlyPreferredDictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)?.removeUnigramEntryDynamically(word)
        }

        // Update the spelling cache after unlearning. Words that are removed from user history
        // and appear in no other language model are not considered valid.
        putWordIntoValidSpellingWordCache("unlearnFromUserHistory", word.lowercase(Locale.getDefault()))
    }

    override fun getAllMainDictionaryWordsWithFrequency(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val dictGroup = dictionaryGroups.firstOrNull() ?: return emptyMap()
        val mainDict = dictGroup.getDict(Dictionary.TYPE_MAIN)
        if (mainDict != null) {
            result.putAll(mainDict.getAllWordsWithFrequency())
        }
        val userHistoryDict = dictGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)
        if (userHistoryDict != null) {
            result.putAll(userHistoryDict.getAllWordsWithFrequency())
        }
        val userDict = dictGroup.getSubDict(Dictionary.TYPE_USER)
        if (userDict != null) {
            result.putAll(userDict.getAllWordsWithFrequency())
        }
        return result;
    }

    override fun forEachMainDictionaryWord(consumer: java.util.function.BiConsumer<String, Int>) {
        val dictGroup = dictionaryGroups.firstOrNull() ?: return
        val mainDict = dictGroup.getDict(Dictionary.TYPE_MAIN)
        mainDict?.forEachWord(consumer)
        val userHistoryDict = dictGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)
        userHistoryDict?.forEachWord(consumer)
        val userDict = dictGroup.getSubDict(Dictionary.TYPE_USER)
        userDict?.forEachWord(consumer)
    }

    // TODO: Revise the way to fusion suggestion results.
    override fun getSuggestionResults(
        composedData: ComposedData, ngramContext: NgramContext, keyboard: Keyboard,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, inputStyle: Int
    ): SuggestionResults {
        val proximityInfoHandle = keyboard.proximityInfo.nativeProximityInfo
        val weightOfLangModelVsSpatialModel = floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL)

        val dictGroups = dictionaryGroups
        if (dictGroups.isEmpty()) {
            return SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, ngramContext.isBeginningOfSentenceContext, false)
        }
        val waitForOtherDicts = if (dictGroups.size <= 1) null else CountDownLatch(dictGroups.size - 1)
        val suggestionsArray = Array<List<SuggestedWordInfo>?>(dictGroups.size) { null }
        for (i in 1..dictGroups.lastIndex) {
            scope.launch {
                try {
                    suggestionsArray[i] = getSuggestions(composedData, ngramContext, settingsValuesForSuggestion, sessionId,
                        proximityInfoHandle, weightOfLangModelVsSpatialModel, dictGroups[i])
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying secondary dictionary for locale ${dictGroups[i].locale}", e)
                } finally {
                    waitForOtherDicts?.countDown()
                }
            }
        }
        try {
            suggestionsArray[0] = getSuggestions(composedData, ngramContext, settingsValuesForSuggestion, sessionId,
                proximityInfoHandle, weightOfLangModelVsSpatialModel, dictGroups[0])
        } catch (e: Exception) {
            Log.e(TAG, "Error querying primary dictionary for locale ${dictGroups[0].locale}", e)
        }
        val suggestionResults = SuggestionResults(
            SuggestedWords.MAX_SUGGESTIONS, ngramContext.isBeginningOfSentenceContext, false
        )
        waitForOtherDicts?.await(500, TimeUnit.MILLISECONDS)

        suggestionsArray.forEach {
            if (it == null) return@forEach
            suggestionResults.addAll(it)
            suggestionResults.mRawSuggestions?.addAll(it)
        }

        // Apply session word boost to suggestion scores
        val boost = sessionWordBoost
        if (boost != null && composedData.mTypedWord.isNotEmpty()) {
            applySessionBoost(suggestionResults, boost)
        }

        includeAtLeastTwoWordSuggestions(suggestionResults, suggestionsArray, composedData.mTypedWord)

        return suggestionResults
    }

    private fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext,
        settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int,
        proximityInfoHandle: Long, weightOfLangModelVsSpatialModel: FloatArray, dictGroup: DictionaryGroup
    ): List<SuggestedWordInfo> {
        val suggestions = ArrayList<SuggestedWordInfo>()
        val weightForLocale = dictGroup.getWeightForLocale(dictionaryGroups, composedData.mIsBatchMode)
        for (dictType in DictionaryFacilitator.ALL_DICTIONARY_TYPES) {
            val dictionary = dictGroup.getDict(dictType) ?: continue
            var dictionarySuggestions = dictionary.getSuggestions(composedData, ngramContext, proximityInfoHandle,
                settingsValuesForSuggestion, sessionId, weightForLocale, weightOfLangModelVsSpatialModel
            )
            if (composedData.mTypedWord.isEmpty() && (dictionarySuggestions == null || dictionarySuggestions.isEmpty())
                && dictType == Dictionary.TYPE_USER
            ) {
                if (!Settings.getValues().mNextWordStrictNgram && Settings.getValues().mPrioritizePersonalSuggestions) {
                    val allWords = try {
                        dictionary.allWordsWithFrequency
                    } catch (e: Exception) {
                        null
                    }
                    if (allWords != null && allWords.isNotEmpty()) {
                        val topWords = allWords.entries
                            .sortedByDescending { it.value }
                            .take(15)
                        val unigramSuggestions = ArrayList<SuggestedWordInfo>()
                        for (entry in topWords) {
                            unigramSuggestions.add(
                                SuggestedWordInfo(
                                    entry.key,
                                    "",
                                    entry.value,
                                    SuggestedWordInfo.KIND_PREDICTION,
                                    dictionary,
                                    SuggestedWordInfo.NOT_AN_INDEX,
                                    SuggestedWordInfo.NOT_A_CONFIDENCE
                                )
                            )
                        }
                        dictionarySuggestions = unigramSuggestions
                    }
                }
            }
            if (dictionarySuggestions == null) continue

            // For some reason "garbage" words are produced when glide typing. For user history
            // and main dictionaries we can filter them out by checking whether the dictionary
            // actually contains the word. But personal and addon dictionaries may contain shortcuts,
            // which do not pass an isInDictionary check (e.g. emojis).
            // (if the main dict contains shortcuts to non-words, this will break!)
            val checkForGarbage = composedData.mIsBatchMode && (dictType == Dictionary.TYPE_USER_HISTORY || dictType == Dictionary.TYPE_MAIN)

            for (info in dictionarySuggestions) {
                val word = info.word
                if (!Settings.getValues().mSuggestEmojis && (info.isEmoji || info.mSourceDict?.mDictType == Dictionary.TYPE_EMOJI))
                    continue
                if (isBlacklisted(word) || SupportedEmojis.isUnsupported(word)) // don't add blacklisted words and unsupported emojis
                    continue
                if (checkForGarbage
                    // consider the user might use custom main dictionary containing shortcuts
                    //  assume this is unlikely to happen, and take care about common shortcuts that are not actual words (emoji, symbols)
                    && word.length > 2 // should exclude most symbol shortcuts
                    && info.mSourceDict.mDictType == dictType // dictType is always main, but info.mSourceDict.mDictType contains the actual dict (main dict is a dictionary group)
                    && !mightBeEmoji(word) // simplified check for performance reasons
                    && !dictionary.isInDictionary(word)
                )
                    continue

                if (word.length == 1 && info.mSourceDict.mDictType == Dictionary.TYPE_EMOJI && !StringUtils.mightBeEmoji(word[0].code))
                    continue

                if (composedData.mTypedWord.isEmpty() && (dictType == Dictionary.TYPE_USER_HISTORY || dictType == Dictionary.TYPE_USER)) {
                    val settingsValues = Settings.getValues()
                    val boostedScore = if (settingsValues.mPrioritizePersonalSuggestions) {
                        info.mScore + settingsValues.mNextWordBoostLevel
                    } else {
                        info.mScore
                    }
                    val boostedInfo = SuggestedWordInfo(
                        info.mWord, info.mPrevWordsContext, boostedScore, info.mKindAndFlags,
                        info.mSourceDict, info.mIndexOfTouchPointOfSecondWord, info.mAutoCommitFirstWordConfidence
                    )
                    suggestions.add(boostedInfo)
                } else {
                    suggestions.add(info)
                }
            }
        }
        // AI next-word candidates, only in prediction/next-word mode. The dictionary returns
        // null (or filtered-away empties) unless it has cached LLM candidates, so it never
        // blocks this pass. AOSP suggestions always come first (added above).
        if (composedData.mTypedWord.isEmpty()) {
            aiNextWordDict?.let { dict ->
                dict.prevTextForPrompt = aiNextWordContextText
                dict.appPackageName = aiNextWordAppPackage
                dict.appLanguageHint = aiNextWordAppLanguage
                dict.noLearning = aiNextWordNoLearning
                dict.appContext = if (aiNextWordNoLearning) null else aiNextWordPersistedContext
                val aiInfos = dict.getSuggestions(
                    composedData, ngramContext, proximityInfoHandle, settingsValuesForSuggestion,
                    sessionId, weightForLocale, weightOfLangModelVsSpatialModel
                )?.filter { it.word.isNotEmpty() }
                if (!aiInfos.isNullOrEmpty()) {
                    // Rank AI words BELOW every AOSP suggestion in this list. AOSP next-word scores
                    // vary wildly (history ~700-800, user dict can be millions), so a fixed base is
                    // unreliable — instead key off the actual AOSP scores present so AI words ALWAYS
                    // append as extra choices and can never displace or reorder the AOSP suggestions
                    // the user is about to tap. mScore is final, so rebuild the infos with lower
                    // (strictly decreasing, hence unique within the TreeSet) scores.
                    val floor = ((suggestions.minOfOrNull { it.mScore } ?: AI_NEXT_WORD_BASE_SCORE) - 1)
                    aiInfos.forEachIndexed { i, info ->
                        val score = floor - i
                        suggestions.add(
                            if (info.mScore == score) info
                            else SuggestedWordInfo(
                                info.mWord, info.mPrevWordsContext, score, info.mKindAndFlags,
                                info.mSourceDict, info.mIndexOfTouchPointOfSecondWord,
                                info.mAutoCommitFirstWordConfidence
                            )
                        )
                    }
                }
            }
        }
        return suggestions
    }

    /**
     * Apply session word boost to suggestion results.
     * Creates new SuggestedWordInfo objects with boosted scores for words
     * that the user has typed recently. Since mScore is final, we must
     * remove and re-add entries with adjusted scores.
     */
    private fun applySessionBoost(results: SuggestionResults, boost: SessionWordBoost) {
        val boosted = mutableListOf<SuggestedWordInfo>()
        val toRemove = mutableListOf<SuggestedWordInfo>()
        for (info in results) {
            val boostAmount = boost.getBoost(info.mWord)
            if (boostAmount > 0f) {
                toRemove.add(info)
                boosted.add(SuggestedWordInfo(
                    info.mWord, info.mPrevWordsContext,
                    info.mScore + (boostAmount * BOOST_SCORE_MULTIPLIER).toInt(),
                    info.mKindAndFlags, info.mSourceDict,
                    info.mIndexOfTouchPointOfSecondWord,
                    info.mAutoCommitFirstWordConfidence
                ))
            }
        }
        for (item in toRemove) {
            results.remove(item)
        }
        for (item in boosted) {
            results.add(item)
        }
    }

    // Spell checker is using this, and has its own instance of DictionaryFacilitatorImpl,
    // meaning that it always has default mConfidence. So we cannot choose to only check preferred
    // locale, and instead simply return true if word is in any of the available dictionaries
    override fun isValidSpellingWord(word: String): Boolean {
        mValidSpellingWordReadCache?.get(word)?.let { return it }
        mValidSpellingWordWriteCache?.get(word)?.let { return it }
        val result = dictionaryGroups.any { isValidWord(word, SPELLING_DICTIONARY_TYPES, it) }
        mValidSpellingWordReadCache?.put(word, result)
        return result
    }

    // this is unused, so leave it for now (redirecting to isValidWord seems to defeat the purpose...)
    override fun isValidSuggestionWord(word: String): Boolean {
        return isValidWord(word, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroups[0])
    }

    // todo: move into dictionaryGroup?
    private fun isValidWord(word: String, dictionariesToCheck: Array<String>, dictionaryGroup: DictionaryGroup): Boolean {
        if (word.isEmpty() || dictionaryGroup.isBlacklisted(word)) return false
        return dictionariesToCheck.any { dictionaryGroup.getDict(it)?.isValidWord(word) == true }
    }

    override fun isBlacklisted(word: String): Boolean = dictionaryGroups.any { it.isBlacklisted(word) }

    override fun removeWord(word: String) {
        for (dictionaryGroup in dictionaryGroups) {
            dictionaryGroup.removeWord(word)
        }
    }

    override fun reloadBlacklist() {
        for (dictionaryGroup in dictionaryGroups) {
            dictionaryGroup.reloadBlacklist()
        }
    }

    override fun clearUserHistoryDictionary(context: Context) {
        for (dictionaryGroup in dictionaryGroups) {
            dictionaryGroup.getSubDict(Dictionary.TYPE_USER_HISTORY)?.clear()
        }
    }

    override fun localesAndConfidences(): String? {
        if (dictionaryGroups.size < 2) return null
        return dictionaryGroups.joinToString(", ") { "${it.locale} ${it.confidence}" }
    }

    override fun dumpDictionaryForDebug(dictName: String) {
        val dictToDump = dictionaryGroups[0].getSubDict(dictName)
        if (dictToDump == null) {
            Log.e(TAG, ("Cannot dump $dictName. The dictionary is not being used for suggestion or cannot be dumped."))
            return
        }
        dictToDump.dumpAllWordsForDebug()
    }

    override fun getDictionaryStats(context: Context): List<DictionaryStats> =
        DictionaryFacilitator.DYNAMIC_DICTIONARY_TYPES.flatMap { dictType ->
            dictionaryGroups.mapNotNull { it.getSubDict(dictType)?.dictionaryStats }
        }

    override fun dump(context: Context) = getDictionaryStats(context).joinToString("\n")

    companion object {
        private val TAG = DictionaryFacilitatorImpl::class.java.simpleName
        // Fallback base when there are no AOSP suggestions to rank AI words below.
        private const val AI_NEXT_WORD_BASE_SCORE = 1000

        // HACK: This threshold is being used when adding a capitalized entry in the User History dictionary.
        private const val CAPITALIZED_FORM_MAX_PROBABILITY_FOR_INSERT = 140

        // Multiplier to convert session boost values into score-space (native scores are ~1_000_000)
        private const val BOOST_SCORE_MULTIPLIER = 1000f

        private fun createSubDict(
            dictType: String, context: Context, locale: Locale, dictFile: File?, dictNamePrefix: String
        ): ExpandableBinaryDictionary? {
            try {
                return when (dictType) {
                    Dictionary.TYPE_USER_HISTORY -> UserHistoryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    Dictionary.TYPE_USER -> UserBinaryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    Dictionary.TYPE_CONTACTS -> ContactsBinaryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    Dictionary.TYPE_APPS -> AppsBinaryDictionary.getDictionary(context, locale, dictFile, dictNamePrefix)
                    else -> throw IllegalArgumentException("unknown dictionary type $dictType")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot create dictionary: $dictType", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Cannot create dictionary: $dictType", e)
            }
            return null
        }

        private fun findDictionaryGroupWithLocale(dictGroups: List<DictionaryGroup>?, locale: Locale): DictionaryGroup? {
            return dictGroups?.firstOrNull { it.locale == locale }
        }

        private fun getUsedLocales(mainLocale: Locale, context: Context): Collection<Locale> {
            val locales = hashSetOf(mainLocale)
            // adding secondary locales is a bit tricky since they depend on the subtype
            // but usually this is called with the selected subtype locale
            val selectedSubtype = SubtypeSettings.getSelectedSubtype(context.prefs())
            if (selectedSubtype.locale() == mainLocale) {
                locales.addAll(getSecondaryLocales(selectedSubtype.extraValue))
            } else {
                // probably we're called from the spell checker when using a different app as keyboard
                // so best bet is adding all secondary locales for matching main locale
                SubtypeSettings.getEnabledSubtypes(false).forEach {
                    if (it.locale() == mainLocale)
                        locales.addAll(getSecondaryLocales(it.extraValue))
                }
            }
            return locales
        }

        /** Include at least two non-emoji, non-typed word results if possible, so that the first two shown suggestions can be non-emoji */
        private fun includeAtLeastTwoWordSuggestions(
            suggestionResults: SuggestionResults,
            suggestionsArray: Array<List<SuggestedWordInfo>?>,
            typedWord: String
        ) {
            if (suggestionResults.size <= 2) return
            var nonEmojiNonTypedWordCount = 0
            suggestionResults.forEach {
                if (isEmojiOrTypedWord(it, typedWord)) return@forEach
                ++nonEmojiNonTypedWordCount
                if (nonEmojiNonTypedWordCount >= 2) return
            }
            val allResults = SuggestionResults(Int.MAX_VALUE, false, false)
            suggestionsArray.forEach {
                if (it == null) return@forEach
                allResults.addAll(it)
            }
            var addedWord: String? = null
            for (i in 0 until 2 - nonEmojiNonTypedWordCount) {
                val firstNonEmojiNonTypedWord = allResults.firstOrNull {
                    !suggestionResults.contains(it) && !isEmojiOrTypedWord(it, typedWord)
                        && addedWord?.compareTo(it.word, true) != 0
                } ?: continue
                // The conditions above guarantee that there are at least two EmojiOrTypedWord items
                val lastEmojiOrTypedWord = suggestionResults.last { isEmojiOrTypedWord(it, typedWord) }
                suggestionResults.remove(lastEmojiOrTypedWord)
                suggestionResults.add(firstNonEmojiNonTypedWord)
                addedWord = firstNonEmojiNonTypedWord.word
            }
        }

        private fun isEmojiOrTypedWord(info: SuggestedWordInfo, typedWord: String): Boolean =
            info.isEmoji || info.word.compareTo(typedWord, true) == 0
    }
}

/** A group of dictionaries that work together for a single language. */
private class DictionaryGroup(
    val locale: Locale = Locale(""),
    private var mainDict: Dictionary? = null,
    subDicts: Map<String, ExpandableBinaryDictionary> = emptyMap(),
    context: Context? = null
) {
    private val subDicts: ConcurrentHashMap<String, ExpandableBinaryDictionary> = ConcurrentHashMap(subDicts)

    // Monitor for the blacklist set + file I/O. The previous code used
    // `synchronized(this)` inside an `apply { }` and `scope.launch { }` block, which
    // re-bound `this` to the inner receiver (the HashSet / CoroutineScope). Two
    // concurrent blacklist operations could then run without mutual exclusion.
    private val blacklistLock = Any()

    /** Removes a word from all dictionaries in this group. If the word is in a read-only dictionary, it is blacklisted. */
    fun removeWord(word: String) {
        addToBlacklist(word)
        val lowercase = word.lowercase(locale)
        if (word != lowercase) {
            addToBlacklist(lowercase)
        }

        // remove from user history
        getSubDict(Dictionary.TYPE_USER_HISTORY)?.removeUnigramEntryDynamically(word)

        // and from personal dictionary
        getSubDict(Dictionary.TYPE_USER)?.removeUnigramEntryDynamically(word)

        val contactsDict = getSubDict(Dictionary.TYPE_CONTACTS)
        if (contactsDict != null && contactsDict.isInDictionary(word)) {
            contactsDict.removeUnigramEntryDynamically(word) // will be gone until next reload of dict
        }

        val appsDict = getSubDict(Dictionary.TYPE_APPS)
        if (appsDict != null && appsDict.isInDictionary(word)) {
            appsDict.removeUnigramEntryDynamically(word) // will be gone until next reload of dict
        }
    }

    // --------------- Confidence for multilingual typing -------------------

    // Confidence that the most probable language is actually the language the user is
    // typing in. For now, this is simply the number of times a word from this language
    // has been committed in a row, with an exception when typing a single word not contained
    // in this language.
    var confidence = 1

    // allow to go above max confidence, for better determination of currently preferred language
    // when decreasing confidence or getting weight factor, limit to maximum
    fun increaseConfidence() {
        confidence += 1
    }

    // If confidence is above max, drop to max confidence. This does not change weights and
    // allows conveniently typing single words from the other language without affecting suggestions
    fun decreaseConfidence() {
        if (confidence > MAX_CONFIDENCE) confidence = MAX_CONFIDENCE
        else if (confidence > 0) {
            confidence -= 1
        }
    }

    fun getWeightForLocale(groups: List<DictionaryGroup>, isGesturing: Boolean) =
        getWeightForLocale(groups, if (isGesturing) 0.05f else 0.15f)

    // might need some more tuning
    fun getWeightForLocale(groups: List<DictionaryGroup>, step: Float): Float {
        if (groups.size == 1) return 1f
        if (confidence < 2) return 1f - step * (MAX_CONFIDENCE - confidence)
        for (group in groups) {
            if (group !== this && group.confidence >= confidence) return 1f - step / 2f
        }
        return 1f
    }

    // --------------- Blacklist -------------------

    // Limit parallelism to prevent excessive I/O operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    // words cannot be (permanently) removed from some dictionaries, so we use a blacklist for "removing" words
    private val blacklistFile = if (context == null) null
    else {
        val file = File(DeviceProtectedUtils.getFilesDir(context).absolutePath + File.separator + "blacklists" + File.separator + locale.toLanguageTag() + ".txt")
        if (file.isDirectory) file.delete() // this apparently was an issue in some versions
        if (file.parentFile?.exists() == true || file.parentFile?.mkdirs() == true) file
        else null
    }

    @Volatile
    private var compiledBlacklistPatterns: List<Regex> = emptyList()

    private fun rebuildCompiledPatterns() {
        rebuildCompiledPatterns(blacklist)
    }

    private fun rebuildCompiledPatterns(patterns: Collection<String>) {
        compiledBlacklistPatterns = patterns.map { pattern ->
            try {
                Regex(pattern)
            } catch (e: Exception) {
                Regex(Regex.escape(pattern))
            }
        }
    }

    private val blacklist = hashSetOf<String>().apply {
        val file = blacklistFile
        if (file == null) return@apply
        scope.launch {
            synchronized(blacklistLock) {
                try {
                    val loadedWords = mutableSetOf<String>()
                    if (file.isFile) {
                        loadedWords.addAll(file.readLines().map { it.lowercase(locale) })
                    }
                    val langTag = locale.toLanguageTag()
                    if (locale.language.isNotEmpty() && locale.language != langTag) {
                        val baseFile = File(file.parentFile, "${locale.language}.txt")
                        if (baseFile.isFile) {
                            loadedWords.addAll(baseFile.readLines().map { it.lowercase(locale) })
                        }
                    }
                    addAll(loadedWords)
                    rebuildCompiledPatterns(this@apply)
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while trying to read blacklist from ${file.name}", e)
                }
            }
        }
    }

    fun isBlacklisted(word: String): Boolean {
        val userDict = getSubDict(Dictionary.TYPE_USER)
        val lowercased = word.lowercase(locale)
        if (userDict != null && (userDict.isInDictionary(word) || userDict.isInDictionary(lowercased))) {
            return false
        }
        val patterns = compiledBlacklistPatterns
        return patterns.any { it.matches(lowercased) }
    }

    fun addToBlacklist(word: String) {
        val lowercase = word.lowercase(locale)
        synchronized(blacklistLock) {
            if (!blacklist.add(lowercase) || blacklistFile == null) return
            rebuildCompiledPatterns()
        }
        val file = blacklistFile ?: return
        scope.launch {
            synchronized(blacklistLock) {
                try {
                    if (file.isDirectory) file.delete()
                    file.appendText("$lowercase\n")
                    val langTag = locale.toLanguageTag()
                    if (locale.language.isNotEmpty() && locale.language != langTag) {
                        val baseFile = File(file.parentFile, "${locale.language}.txt")
                        if (baseFile.isDirectory) baseFile.delete()
                        baseFile.appendText("$lowercase\n")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while trying to add word \"$lowercase\" to blacklist ${file.name}", e)
                }
            }
        }
    }

    fun removeFromBlacklist(word: String) {
        val lowercase = word.lowercase(locale)
        synchronized(blacklistLock) {
            if (!blacklist.remove(lowercase) || blacklistFile == null) return
            rebuildCompiledPatterns()
        }
        val file = blacklistFile ?: return
        scope.launch {
            synchronized(blacklistLock) {
                try {
                    val files = mutableListOf(file)
                    val langTag = locale.toLanguageTag()
                    if (locale.language.isNotEmpty() && locale.language != langTag) {
                        files.add(File(file.parentFile, "${locale.language}.txt"))
                    }
                    for (f in files) {
                        if (f.isFile) {
                            val lines = f.readLines()
                            val newLines = lines.filterNot { it.lowercase(locale) == lowercase }
                            if (newLines.size != lines.size) {
                                f.writeText(newLines.joinToString("\n") + if (newLines.isEmpty()) "" else "\n")
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while trying to remove word \"$word\" from blacklist ${file.name}", e)
                }
            }
        }
    }

    fun reloadBlacklist() {
        val file = blacklistFile
        if (file == null) {
            synchronized(blacklistLock) {
                blacklist.clear()
                rebuildCompiledPatterns()
            }
            return
        }
        scope.launch {
            synchronized(blacklistLock) {
                try {
                    blacklist.clear()
                    val loadedWords = mutableSetOf<String>()
                    if (file.isFile) {
                        loadedWords.addAll(file.readLines().map { it.lowercase(locale) })
                    }
                    val langTag = locale.toLanguageTag()
                    if (locale.language.isNotEmpty() && locale.language != langTag) {
                        val baseFile = File(file.parentFile, "${locale.language}.txt")
                        if (baseFile.isFile) {
                            loadedWords.addAll(baseFile.readLines().map { it.lowercase(locale) })
                        }
                    }
                    blacklist.addAll(loadedWords)
                    rebuildCompiledPatterns()
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while trying to read blacklist from ${file.name}", e)
                }
            }
        }
    }

    // --------------- Dictionary handling -------------------

    fun setMainDict(newMainDict: Dictionary?) {
        // Close old dictionary if exists. Main dictionary can be assigned multiple times.
        val oldDict = mainDict
        mainDict = newMainDict
        if (oldDict != null && newMainDict !== oldDict)
            oldDict.close()
    }

    fun getDict(dictType: String): Dictionary? {
        if (dictType == Dictionary.TYPE_MAIN) {
            return mainDict
        }
        return getSubDict(dictType)
    }

    fun getSubDict(dictType: String): ExpandableBinaryDictionary? {
        return subDicts[dictType]
    }

    fun hasDict(dictType: String): Boolean {
        if (dictType == Dictionary.TYPE_MAIN) {
            return mainDict != null
        }
        return subDicts.containsKey(dictType)
    }

    fun closeDict(dictType: String) {
        val dict = if (Dictionary.TYPE_MAIN == dictType) {
            mainDict
        } else {
            subDicts.remove(dictType)
        }
        dict?.close()
    }

    companion object {
        private val TAG = DictionaryGroup::class.java.simpleName
        const val MAX_CONFIDENCE = 2
    }
}
