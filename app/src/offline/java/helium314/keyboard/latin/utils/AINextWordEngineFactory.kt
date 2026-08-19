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
        // Engine is usable only once a GGUF model is loaded (offline flavor).
        if (!ProofreadService.ModelHolder.isModelLoaded) {
            return null
        }
        return OfflineNextWordEngine(context.applicationContext)
    }
}

private class OfflineNextWordEngine(private val context: Context) : AINextWordEngine {

    override fun isReady(): Boolean = ProofreadService.ModelHolder.isModelLoaded

    override suspend fun suggestNextWords(prompt: String): List<String> = withContext(Dispatchers.IO) {
        val helper = ProofreadService.ModelHolder.llamaHelper ?: return@withContext emptyList()
        val result = try {
            val completionText = completeWithParams(helper, prompt)
            splitCandidates(completionText)
        } catch (e: Exception) {
            emptyList()
        }
        // Keep the model warm/per policy just like proofreading does.
        ProofreadService.ModelHolder.scheduleUnload(context)
        result
    }

    /** Mirrors ProofreadService.predictWithParams + flow collection for proofreading. */
    private suspend fun completeWithParams(helper: LlamaHelper, prompt: String): String {
        val currentContextField =
            LlamaHelper::class.java.getDeclaredField("currentContext").apply { isAccessible = true }
        val currentContext = currentContextField.get(helper) as? Int ?: return ""

        val llamaField =
            LlamaHelper::class.java.getDeclaredField("llama\$delegate").apply { isAccessible = true }
        val llama = llamaField.get(helper) as Lazy<org.nehuatl.llamacpp.LlamaAndroid>

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
                llama.value.launchCompletion(currentContext, params)
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
        // Collect from ModelHolder.llmFlow (the same buffered flow proofreading trusts). A timeout
        // guards against a missing terminal event so this can never stall the suggestion pipeline.
        withTimeoutOrNull(NEXT_WORD_TIMEOUT_MS) {
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

private const val NEXT_WORD_TIMEOUT_MS = 8000L
