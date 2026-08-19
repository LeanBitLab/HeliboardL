/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

/**
 * Provider-agnostic contract for the AI next-word suggestion source.
 *
 * Implementations live per-flavor (standard = cloud, offline = on-device GGUF,
 * offlinelite = stub) via [AINextWordEngineFactory].
 */
interface AINextWordEngine {
    /** Whether the engine is ready to produce candidate words right now. */
    fun isReady(): Boolean

    /**
     * Produces the next-word continuation for the given plain-text prompt.
     * Called from the AI next-word dictionary's own coroutine scope, never from
     * the suggestion thread. Should return an empty list on any failure.
     */
    suspend fun suggestNextWords(prompt: String): List<String>
}
