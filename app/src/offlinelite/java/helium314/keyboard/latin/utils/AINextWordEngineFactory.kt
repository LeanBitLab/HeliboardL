/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * Offlinelite-flavor AI next-word engine factory. This flavor ships no AI, so the factory returns
 * null. Kept with the same FQCN as the other flavors so the main source set compiles in all three.
 */
object AINextWordEngineFactory {

    fun create(context: Context): AINextWordEngine? {
        if (!context.prefs().getBoolean(Settings.PREF_AI_NEXT_WORD, Defaults.PREF_AI_NEXT_WORD)) {
            return null
        }
        return NoopNextWordEngine
    }
}

private object NoopNextWordEngine : AINextWordEngine {
    override fun isReady(): Boolean = true
    override suspend fun suggestNextWords(prompt: String): List<String> = emptyList()
}
