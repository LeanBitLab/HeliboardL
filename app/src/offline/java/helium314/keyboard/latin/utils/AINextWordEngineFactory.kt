/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.takeWhile
import org.nehuatl.llamacpp.LlamaHelper
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

private const val TAG = "AINextWord"

/**
 * Offline-flavor AI next-word engine factory. Produces causal next-word continuations using the
 * same on-device GGUF LlamaHelper runtime that [ProofreadService.ModelHolder] manages for
 * proofreading, so a model already loaded by the proofread UI is reused — no network.
 */
object AINextWordEngineFactory {

    fun create(context: Context): AINextWordEngine? {
        if (!context.prefs().getBoolean(Settings.PREF_AI_NEXT_WORD, Defaults.PREF_AI_NEXT_WORD)) {
            return null
        }
        // Engine is created whenever the pref is on. Readiness is gated dynamically via
        // isReady() (which reflects ModelHolder.isModelLoaded), so the AI dictionary exists from
        // the toggle and becomes live the moment a GGUF model is loaded for proofreading — no
        // dependency on model being loaded at dict-build time.
        return OfflineNextWordEngine(context.applicationContext)
    }
}

private class OfflineNextWordEngine(private val context: Context) : AINextWordEngine {

    // Guards against launching two llama completions on the same native context at once (which
    // crashes the process). Only one next-word completion runs at a time; concurrent ones are
    // dropped, not queued.
    private val completionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun isReady(): Boolean = ProofreadService.ModelHolder.isModelLoaded

    override suspend fun suggestNextWords(prompt: String): List<String> = withContext(Dispatchers.IO) {
        Log.i(TAG, "AINextWord: suggestNextWords prompt='$prompt' modelLoaded=${ProofreadService.ModelHolder.isModelLoaded} path=${ProofreadService.ModelHolder.currentModelPath}")
        // Serialize completions: two launchCompletion calls on the SAME native LlamaHelper context
        // concurrently crash the process. If a previous next-word completion is still running, drop
        // this request instead of colliding with it.
        if (!completionInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "AINextWord: previous completion still running, dropping request")
            return@withContext emptyList()
        }
        try {
            val helper = ProofreadService.ModelHolder.llamaHelper ?: run {
                Log.w(TAG, "AINextWord: llamaHelper is null, returning empty")
                return@withContext emptyList()
            }
            val result = try {
                val completionText = completeWithParams(helper, prompt)
                val words = splitCandidates(completionText)
                Log.i(TAG, "AINextWord: completion='$completionText' -> candidates=$words")
                words
            } catch (e: Exception) {
                Log.e(TAG, "AINextWord: completion failed", e)
                emptyList()
            }
            Log.i(TAG, "AINextWord: returning ${result.size} candidates")
            // Keep the model warm/per policy just like proofreading does.
            ProofreadService.ModelHolder.scheduleUnload(context)
            result
        } finally {
            completionInFlight.set(false)
        }
    }

    /** Mirrors ProofreadService.predictWithParams + flow collection for proofreading. */
    private suspend fun completeWithParams(helper: LlamaHelper, prompt: String): String {
        val currentContextField =
            LlamaHelper::class.java.getDeclaredField("currentContext").apply { isAccessible = true }
        val currentContext = currentContextField.get(helper) as? Int ?: return ""

        val llamaField =
            LlamaHelper::class.java.getDeclaredField("llama\$delegate").apply { isAccessible = true }
        // Initialise the native wrapper on THIS (calling) thread, mirroring ProofreadService
        // predictWithParams. Touching .value lazily on a worker coroutine thread can mis-init
        // the native llama context and crash.
        val llama = (llamaField.get(helper) as Lazy<org.nehuatl.llamacpp.LlamaAndroid>).value

        val tokenCountField =
            LlamaHelper::class.java.getDeclaredField("tokenCount").apply { isAccessible = true }
        tokenCountField.set(helper, 0)
        val allTextField =
            LlamaHelper::class.java.getDeclaredField("allText").apply { isAccessible = true }
        allTextField.set(helper, "")

        val params = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "emit_partial_completion" to true,
            "temperature" to 0.2,
            "top_p" to 0.9,
            "top_k" to 40,
            "min_p" to 0.05,
            "n_predict" to 16,
            "stop" to listOf("\n", ". ", ".")
        )

        // Emit Started so the collected flow carries the terminal event (mirrors
        // ProofreadService.predictWithParams, which emits Started before launching).
        helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Started(prompt))

        val completionJobField =
            LlamaHelper::class.java.getDeclaredField("completionJob").apply { isAccessible = true }
        val job = helper.scope.launch {
            val startTime = System.currentTimeMillis()
            try {
                // Serialize against every other native completion (proofreading + next-word
                // share this one LlamaHelper context). Concurrent generation on the same
                // context corrupts the native heap / null-derefs inside doCompletion.
                synchronized(ProofreadService.ModelHolder.completionLock) {
                    llama.launchCompletion(currentContext, params)
                }
            } catch (e: Throwable) {
                helper.sharedFlow.tryEmit(
                    LlamaHelper.LLMEvent.Error("Next word completion failed: ${e.message}")
                )
                return@launch
            }
            val allText = allTextField.get(helper) as String
            val tokenCount = tokenCountField.get(helper) as Int
            helper.sharedFlow.tryEmit(
                LlamaHelper.LLMEvent.Done(allText, tokenCount, System.currentTimeMillis() - startTime)
            )
        }
        completionJobField.set(helper, job)

        val generated = StringBuilder()
        val start = System.currentTimeMillis()
        val finished = withTimeoutOrNull(NEXT_WORD_TIMEOUT_MS) {
            ProofreadService.ModelHolder.llmFlow.takeWhile { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        generated.append(event.word)
                        true
                    }
                    is LlamaHelper.LLMEvent.Done -> false
                    is LlamaHelper.LLMEvent.Error -> false
                    else -> true
                }
            }.collect { }
        } != null
        val elapsed = System.currentTimeMillis() - start
        if (!finished) {
            Log.w(TAG, "AINextWord: completion TIMED OUT after ${elapsed}ms (max $NEXT_WORD_TIMEOUT_MS), partial='$generated'")
        } else {
            Log.i(TAG, "AINextWord: completion finished in ${elapsed}ms, text='$generated'")
        }
        return generated.toString()
    }

    private fun splitCandidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in raw.split(Regex("[\\s,;:!?\\.]+"))) {
            val word = token.trim().trim('\'', '"')
            if (word.isNotEmpty() && word.any { it.isLetter() }) out.add(word)
            if (out.size >= 3) break
        }
        return out.toList()
    }
}

private const val NEXT_WORD_TIMEOUT_MS = 30000L
