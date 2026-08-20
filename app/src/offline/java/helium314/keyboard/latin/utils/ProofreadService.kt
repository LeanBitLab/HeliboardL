/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File

/**
 * Offline proofreading service using llamacpp-kotlin with GGUF models.
 *
 * Uses LlamaHelper for on-device inference with llama.cpp backend.
 * Supports any GGUF model for text correction/generation.
 *
 * Expected model files:
 * - Any GGUF format model file
 */
class ProofreadService(private val context: Context) {

    private val sharedPrefs: SharedPreferences by lazy {
        context.prefs()
    }

    fun getPrefs(): SharedPreferences = sharedPrefs
    
    // Singleton holder for model state to prevent reloading on every request
    object ModelHolder {
        var llamaHelper: LlamaHelper? = null
        var currentModelPath: String? = null
        var isModelAvailable: Boolean = true
        var isModelLoaded: Boolean = false

        // Smart Unload Logic
        private var unloadJob: Job? = null
        private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private const val UNLOAD_DELAY_MS = 10 * 60 * 1000L // 10 minutes
        private val loadMutex = Mutex()

        // Guards ALL native llama completions (proofreading + AI next-word) so two can never
        // run concurrently on the single shared LlamaHelper context, and unload can never run
        // while a completion is mid-flight. llama.cpp is NOT thread-safe for simultaneous
        // generation on the same context: concurrent access corrupts the native heap
        // ("Scudo invalid chunk state" SIGABRT) and null-derefs (SIGSEGV) inside
        // doCompletion — exactly the crashes seen on the AI next-word path.
        val completionLock = Any()

        // Flow for LLM events
        val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        @Synchronized
        fun scheduleUnload(context: Context) {
            unloadJob?.cancel()
            
            val prefs = context.prefs()
            val keepLoaded = prefs.getBoolean(Settings.PREF_OFFLINE_KEEP_MODEL_LOADED, Defaults.PREF_OFFLINE_KEEP_MODEL_LOADED)
            
            if (keepLoaded) {
                 Log.i(TAG, "Model unload skipped (Keep Model Loaded enabled)")
                 return
            }

            unloadJob = scope.launch {
                delay(UNLOAD_DELAY_MS)
                unloadModel()
                Log.i(TAG, "Offline AI model unloaded due to inactivity")
            }
        }

        @Synchronized
        fun cancelUnload() {
            unloadJob?.cancel()
            unloadJob = null
        }

        @Synchronized
        fun unloadModel() {
            try {
                // Don't release the native context while a completion is running on it.
                synchronized(completionLock) {
                    llamaHelper?.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unloading llama model", e)
            }
            llamaHelper = null
            currentModelPath = null
            isModelLoaded = false
            isModelAvailable = true
        }

        suspend fun loadModel(
            context: Context,
            modelPath: String
        ): Boolean = loadMutex.withLock {
            cancelUnload()

            // Check if already loaded with same path
            if (isModelLoaded && currentModelPath == modelPath && llamaHelper != null) {
                return true
            }

            unloadModel() // Ensure clean slate if path changed

            return try {
                val contentResolver = context.contentResolver
                val helper = LlamaHelper(
                    contentResolver,
                    scope,
                    llmFlow
                )

                // Get llama via reflection
                val llamaField = LlamaHelper::class.java.getDeclaredField("llama\$delegate").apply { isAccessible = true }
                val llamaLazy = llamaField.get(helper) as Lazy<org.nehuatl.llamacpp.LlamaAndroid>
                val llama = llamaLazy.value

                // Detach model file descriptor
                val uri = android.net.Uri.parse(modelPath)
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalArgumentException("Failed to open model file descriptor")
                val modelFd = pfd.detachFd()

                // Calculate optimal threads count (4 threads is the sweet spot for mobile CPUs)
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = if (cores <= 4) cores else 4
                
                Log.i(TAG, "Loading GGUF model: threads=$threads (cores=$cores), use_mmap=false")

                // Construct parameters map
                val params = mutableMapOf<String, Any>(
                    "model" to modelPath,
                    "model_fd" to modelFd,
                    "use_mmap" to false,
                    "use_mlock" to false,
                    "n_ctx" to 2048,
                    "embedding" to false,
                    "n_batch" to 512,
                    "n_threads" to threads,
                    "n_gpu_layers" to 0,
                    "vocab_only" to false,
                    "lora" to "",
                    "lora_scaled" to 1.0,
                    "rope_freq_base" to 0.0,
                    "rope_freq_scale" to 0.0
                )

                // JNI callback called by native code for each token
                val callback: (String) -> Unit = { word ->
                    try {
                        val allTextField = LlamaHelper::class.java.getDeclaredField("allText").apply { isAccessible = true }
                        val currentAllText = allTextField.get(helper) as String
                        allTextField.set(helper, currentAllText + word)

                        val tokenCountField = LlamaHelper::class.java.getDeclaredField("tokenCount").apply { isAccessible = true }
                        val currentCount = tokenCountField.get(helper) as Int
                        tokenCountField.set(helper, currentCount + 1)

                        helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Ongoing(word, currentCount + 1))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in native token callback", e)
                    }
                }

                // Start the engine
                val result = llama.startEngine(params, callback)

                val contextId = result?.get("contextId") as? Int
                    ?: throw IllegalStateException("contextId not found in result map")

                // Set currentContext via reflection
                val currentContextField = LlamaHelper::class.java.getDeclaredField("currentContext").apply { isAccessible = true }
                currentContextField.set(helper, contextId)

                // Emit Loaded event
                helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Loaded(modelPath))

                llamaHelper = helper
                currentModelPath = modelPath
                isModelLoaded = true
                isModelAvailable = true
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load GGUF model", e)
                isModelAvailable = false
                false
            }
        }

        private const val TAG = "LlamaProofreadService"
    }

    // AI Provider support (API compatibility)
    enum class AIProvider {
        GEMINI, GROQ, OPENAI
    }
    
    fun getProvider(): AIProvider = AIProvider.GROQ
    fun setProvider(provider: AIProvider) { /* No-op */ }

    suspend fun fetchAvailableModels(provider: AIProvider): List<String> = emptyList()

    // API-compatible methods
    fun getApiKey(): String? = null
    fun setApiKey(apiKey: String?) { /* No-op */ }
    fun hasApiKey(): Boolean = false
    
    // HuggingFace stubs
    fun getHuggingFaceToken(): String? = null
    fun setHuggingFaceToken(token: String?) { /* No-op */ }
    fun getHuggingFaceModel(): String = "Offline Mode"
    fun setHuggingFaceModel(model: String) { /* No-op */ }
    fun getHuggingFaceEndpoint(): String = "Offline Mode"
    fun setHuggingFaceEndpoint(endpoint: String) { /* No-op */ }

    fun getGroqToken(): String? = null
    fun setGroqToken(token: String?) { /* No-op */ }

    fun getGroqModel(): String = "Offline Mode"
    fun setGroqModel(model: String) { /* No-op */ }

    // Model management - single model path (no encoder/decoder split)
    fun getModelPath(): String? = sharedPrefs.getString(KEY_MODEL_PATH, null)
    
    fun setModelPath(path: String?) {
        sharedPrefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_MODEL_PATH)
            } else {
                putString(KEY_MODEL_PATH, path)
            }
            apply()
        }
        ModelHolder.unloadModel()
    }

    // Decoder path (kept for API compatibility, not used with llamacpp)
    fun getDecoderPath(): String? = sharedPrefs.getString(KEY_DECODER_PATH, null)
    
    fun setDecoderPath(path: String?) {
        sharedPrefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_DECODER_PATH)
            } else {
                putString(KEY_DECODER_PATH, path)
            }
            apply()
        }
    }

    // Tokenizer path (not needed with GGUF - tokenizer is embedded)
    fun getTokenizerPath(): String? = sharedPrefs.getString(KEY_TOKENIZER_PATH, null)
    
    fun setTokenizerPath(path: String?) {
        sharedPrefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_TOKENIZER_PATH)
            } else {
                putString(KEY_TOKENIZER_PATH, path)
            }
            apply()
        }
    }

    fun getSystemPrompt(): String = sharedPrefs.getString(Settings.PREF_OFFLINE_SYSTEM_PROMPT, "") ?: ""

    fun setSystemPrompt(prompt: String) {
        sharedPrefs.edit().putString(Settings.PREF_OFFLINE_SYSTEM_PROMPT, prompt).apply()
    }

    fun getTranslateSystemPrompt(): String = sharedPrefs.getString(Settings.PREF_OFFLINE_TRANSLATE_SYSTEM_PROMPT, "") ?: ""

    fun setTranslateSystemPrompt(prompt: String) {
        sharedPrefs.edit().putString(Settings.PREF_OFFLINE_TRANSLATE_SYSTEM_PROMPT, prompt).apply()
    }

    fun getModelName(): String {
        val path = getModelPath()
        if (path.isNullOrBlank()) return "No Model Selected"
        
        if (path.startsWith("content://")) {
            try {
                val uri = Uri.parse(path)
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve content URI name", e)
            }
        }
        
        return File(path).name.takeIf { it.isNotEmpty() } ?: "Local Model"
    }

    fun setModelName(name: String) { /* No-op */ }
    
    fun getTargetLanguage(): String = "English"
    fun setTargetLanguage(language: String) { /* No-op */ }

    fun getTranslateModelName(): String = ""
    fun setTranslateModelName(modelName: String) { /* No-op */ }

    fun getTranslateHuggingFaceModel(): String = ""
    fun setTranslateHuggingFaceModel(modelName: String) { /* No-op */ }

    fun getTranslateGroqModel(): String = ""
    fun setTranslateGroqModel(modelName: String) { /* No-op */ }

    fun unloadModel() {
        ModelHolder.unloadModel()
    }

    /**
     * Run llamacpp inference for translation.
     */
    suspend fun translate(text: String): Result<String> {
        val target = sharedPrefs.getString(Settings.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE, Defaults.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE) ?: Defaults.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE
        val systemPromptTemplate = getTranslateSystemPrompt().takeIf { it.isNotBlank() } ?: Defaults.PREF_OFFLINE_TRANSLATE_SYSTEM_PROMPT
        val prompt = systemPromptTemplate.replace("{lang}", target)
        return proofread(text, overridePrompt = prompt, targetLanguage = target)
    }

    /**
     * Run llamacpp inference for proofreading/text correction.
     */
    suspend fun proofread(
        text: String,
        overridePrompt: String? = null,
        showThinking: Boolean? = null,
        targetLanguage: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelPath = getModelPath()
        if (modelPath.isNullOrBlank()) {
            return@withContext Result.failure(ProofreadException("Model not loaded. Please select a GGUF model file."))
        }

        // Load model (or get cached)
        if (!ModelHolder.loadModel(context, modelPath)) {
             Log.e(TAG, "Model load failed")
             return@withContext Result.failure(ProofreadException("Failed to load model."))
        }

        // Cancel unload timer while working
        ModelHolder.cancelUnload()

        try {
            val maxTokens = sharedPrefs.getInt(Settings.PREF_OFFLINE_MAX_TOKENS, Defaults.PREF_OFFLINE_MAX_TOKENS)
            val temp = sharedPrefs.getFloat(Settings.PREF_OFFLINE_TEMP, Defaults.PREF_OFFLINE_TEMP)
            val topP = sharedPrefs.getFloat(Settings.PREF_OFFLINE_TOP_P, Defaults.PREF_OFFLINE_TOP_P)
            val topK = sharedPrefs.getInt(Settings.PREF_OFFLINE_TOP_K, Defaults.PREF_OFFLINE_TOP_K)
            val minP = sharedPrefs.getFloat(Settings.PREF_OFFLINE_MIN_P, Defaults.PREF_OFFLINE_MIN_P)
            val showThinkingVal = showThinking ?: sharedPrefs.getBoolean(Settings.PREF_OFFLINE_SHOW_THINKING, Defaults.PREF_OFFLINE_SHOW_THINKING)
            
            // Build the prompt
            val systemPrompt = overridePrompt ?: getSystemPrompt()
            val fullPrompt = if (systemPrompt.contains("{text}")) {
                systemPrompt.replace("{text}", text)
            } else if (overridePrompt != null) {
                // Translation or specific override
                val examples = targetLanguage?.let { getTranslationFewShot(it) } ?: emptyList()
                if (examples.isNotEmpty()) {
                    var builder = "Instruction: ${systemPrompt.trim()}\n\n"
                    for (ex in examples) {
                        builder += "Input: ${ex.first}\nOutput: ${ex.second}\n\n"
                    }
                    builder += "Input: $text\nOutput:"
                    builder
                } else {
                    "Instruction: ${systemPrompt.trim()}\n\nInput: $text\nOutput:"
                }
            } else {
                // Default proofreading with few-shot examples for better local model guidance
                val instruction = systemPrompt.ifBlank { "Correct the grammar and spelling of the input text. Keep the SAME language as the input. Do NOT translate. Output only the corrected text, nothing else." }
                val currentLocale = try {
                    RichInputMethodManager.getInstance().currentSubtype.locale.toString()
                } catch (_: Exception) { "" }
                val localExamples = getProofreadFewShot(currentLocale)
                val builder = StringBuilder("Instruction: ${instruction.trim()}\n\n")
                builder.append("Input: heko hw r u\nOutput: Hello, how are you?\n\n")
                for (ex in localExamples) {
                    builder.append("Input: ${ex.first}\nOutput: ${ex.second}\n\n")
                }
                builder.append("Input: $text\nOutput:")
                builder.toString()
            }
            
            // Collect generated text from the flow
            val generatedText = StringBuilder()
            val helper = ModelHolder.llamaHelper
                ?: return@withContext Result.failure(ProofreadException("Model not available"))

            // Use predict with custom parameters
            predictWithParams(
                helper = helper,
                prompt = fullPrompt,
                temp = temp,
                topP = topP,
                topK = topK,
                minP = minP,
                maxTokens = maxTokens,
                showThinking = showThinkingVal
            )
            
            // Collect events until done
            ModelHolder.llmFlow.takeWhile { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        generatedText.append(event.word)
                        true
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        false
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        throw ProofreadException(event.toString())
                    }
                    else -> true
                }
            }.collect {}

            // Schedule unload after work is done
            ModelHolder.scheduleUnload(context)

            val output = generatedText.toString().trim()

            // Robust cleaning of the generated output
            var cleanedOutput = output
            if (cleanedOutput.startsWith(fullPrompt, ignoreCase = true)) {
                cleanedOutput = cleanedOutput.substring(fullPrompt.length).trim()
            } else if (systemPrompt.isNotBlank() && cleanedOutput.startsWith(systemPrompt, ignoreCase = true)) {
                cleanedOutput = cleanedOutput.substring(systemPrompt.length).trim()
                if (cleanedOutput.startsWith(text, ignoreCase = true)) {
                    cleanedOutput = cleanedOutput.substring(text.length).trim()
                }
            }
            
            // Truncate at the first occurrence of subsequent template markers
            val markers = listOf("\nInput:", "\nInstruction:", "\nOutput:", "\nCorrected:", "Input:", "Instruction:", "Output:", "Corrected:")
            for (marker in markers) {
                val idx = cleanedOutput.indexOf(marker, ignoreCase = true)
                if (idx != -1) {
                    if (marker.startsWith("\n") || idx > 0) {
                        cleanedOutput = cleanedOutput.substring(0, idx).trim()
                    }
                }
            }
            
            // Also truncate at any newline followed by a potential template header (e.g., "\nDraft email:", "\nCorrection:")
            val headerRegex = Regex("\\n[a-zA-Z0-9 ]+:")
            val match = headerRegex.find(cleanedOutput)
            if (match != null) {
                cleanedOutput = cleanedOutput.substring(0, match.range.first).trim()
            }
            
            // Also strip common prefixes that the model might generate or echo
            val prefixesToStrip = listOf(
                "Output:", "Corrected:", "Translation:", "Response:", "Result:",
                "Output: ", "Corrected: ", "Translation: ", "Response: ", "Result: "
            )
            for (prefix in prefixesToStrip) {
                if (cleanedOutput.startsWith(prefix, ignoreCase = true)) {
                    cleanedOutput = cleanedOutput.substring(prefix.length).trim()
                    break
                }
            }
            
            // If the model wrapped the output in quotes, strip them
            if (cleanedOutput.startsWith("\"") && cleanedOutput.endsWith("\"")) {
                cleanedOutput = cleanedOutput.substring(1, cleanedOutput.length - 1).trim()
            }
            if (cleanedOutput.startsWith("'") && cleanedOutput.endsWith("'")) {
                cleanedOutput = cleanedOutput.substring(1, cleanedOutput.length - 1).trim()
            }
            
            // Post-process to strip thinking/reasoning tags if showThinkingVal is false
            val finalOutput = if (!showThinkingVal) {
                stripThinkingTags(cleanedOutput)
            } else {
                cleanedOutput
            }

            Log.i(TAG, "proofread: input='$text' prompt='$fullPrompt' generated='$output' final='$finalOutput'")
            if (finalOutput.isNotBlank()) {
                Result.success(finalOutput)
            } else {
                Result.success(text)
            }

        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Cancel completion job if running
                try {
                    val helper = ModelHolder.llamaHelper
                    if (helper != null) {
                        val completionJobField = LlamaHelper::class.java.getDeclaredField("completionJob").apply { isAccessible = true }
                        val completionJob = completionJobField.get(helper) as? Job
                        completionJob?.cancel()
                    }
                } catch (ex: Throwable) {
                    Log.w(TAG, "Failed to cancel completion job", ex)
                }
                throw e
            }
            Log.e(TAG, "Proofread failed", e)
            ModelHolder.scheduleUnload(context) // Ensure we still schedule unload on error
            Result.failure(ProofreadException(e.message ?: "Unknown error"))
        }
    }

    private fun predictWithParams(
        helper: LlamaHelper,
        prompt: String,
        temp: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        maxTokens: Int,
        showThinking: Boolean
    ) {
        try {
            // Get currentContext via reflection
            val currentContextField = LlamaHelper::class.java.getDeclaredField("currentContext").apply { isAccessible = true }
            val currentContext = currentContextField.get(helper) as? Int ?: throw IllegalStateException("Model not loaded yet")

            // Get llama via reflection
            val llamaField = LlamaHelper::class.java.getDeclaredField("llama\$delegate").apply { isAccessible = true }
            val llamaLazy = llamaField.get(helper) as Lazy<org.nehuatl.llamacpp.LlamaAndroid>
            val llama = llamaLazy.value

            // Reset tokenCount and allText
            val tokenCountField = LlamaHelper::class.java.getDeclaredField("tokenCount").apply { isAccessible = true }
            tokenCountField.set(helper, 0)

            val allTextField = LlamaHelper::class.java.getDeclaredField("allText").apply { isAccessible = true }
            allTextField.set(helper, "")

            // Emit Started event
            helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Started(prompt))

            // Build parameters map
            val params = mutableMapOf<String, Any>(
                "prompt" to prompt,
                "emit_partial_completion" to true,
                "temperature" to temp.toDouble(),
                "top_p" to topP.toDouble(),
                "top_k" to topK,
                "min_p" to minP.toDouble(),
                "n_predict" to maxTokens,
                "stop" to listOf("\nInput:", "\nInstruction:", "\nOutput:", "\nCorrected:")
            )

            // Get completionJob field
            val completionJobField = LlamaHelper::class.java.getDeclaredField("completionJob").apply { isAccessible = true }

            // Launch completion using helper.scope
            val job = helper.scope.launch {
                val startTime = System.currentTimeMillis()
                try {
                    // Serialize the native generation against every other completion
                    // (proofreading + next-word share this one context).
                    synchronized(ModelHolder.completionLock) {
                        llama.launchCompletion(currentContext, params)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Completion failed", e)
                    helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Error("Completion failed: ${e.message}"))
                    return@launch
                }
                val duration = System.currentTimeMillis() - startTime
                val allText = allTextField.get(helper) as String
                val tokenCount = tokenCountField.get(helper) as Int
                helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Done(allText, tokenCount, duration))
            }
            completionJobField.set(helper, job)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to setup prediction", e)
            helper.sharedFlow.tryEmit(LlamaHelper.LLMEvent.Error("Failed to setup prediction: ${e.message}"))
        }
    }

    private fun stripThinkingTags(text: String): String {
        return text
            .replace(Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<thought>[\\s\\S]*?</thought>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<reasoning>[\\s\\S]*?</reasoning>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<details>[\\s\\S]*?</details>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanTranslationOutput(text: String): String {
        var cleaned = text.trim()

        // 1. Cut off reasoning / explanation sections at the end
        val reasoningHeaders = listOf(
            "\nReasoning", "\n\nReasoning",
            "\nExplanation", "\n\nExplanation",
            "\nNotes:", "\n\nNotes:",
            "\nJustification:", "\n\nJustification:",
            "\n- The original", "\n\n- The original",
            "\n* The original", "\n\n* The original"
        )
        for (header in reasoningHeaders) {
            val index = cleaned.indexOf(header, ignoreCase = true)
            if (index > 0) {
                cleaned = cleaned.substring(0, index).trim()
            }
        }

        // 2. Strip leading section prefixes
        val prefixRegex = Regex("^(?i)(translated\\s+text:?|translation:?|here\\s+is\\s+the\\s+translation:?)\\s*", RegexOption.MULTILINE)
        cleaned = cleaned.replace(prefixRegex, "").trim()

        // 3. Remove outer quotes if wrapped in quotes
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            if (cleaned.length >= 2) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            }
        }

        return cleaned
    }

    private fun getTranslationFewShot(targetLanguage: String): List<Pair<String, String>> {
        val lang = targetLanguage.trim().lowercase()
        return when {
            lang.contains("french") || lang.contains("français") -> listOf(
                "Hello, how are you?" to "Bonjour, comment allez-vous?",
                "My name is Alex." to "Je m'appelle Alex."
            )
            lang.contains("spanish") || lang.contains("español") -> listOf(
                "Hello, how are you?" to "Hola, ¿cómo estás?",
                "My name is Alex." to "Mi nombre es Alex."
            )
            lang.contains("german") || lang.contains("deutsch") -> listOf(
                "Hello, how are you?" to "Hallo, wie geht es dir?",
                "My name is Alex." to "Mein Name ist Alex."
            )
            lang.contains("italian") || lang.contains("italiano") -> listOf(
                "Hello, how are you?" to "Ciao, come stai?",
                "My name is Alex." to "Il mio nome è Alex."
            )
            lang.contains("portuguese") || lang.contains("português") -> listOf(
                "Hello, how are you?" to "Olá, como você está?",
                "My name is Alex." to "Meu nome é Alex."
            )
            lang.contains("dutch") || lang.contains("nederlands") -> listOf(
                "Hello, how are you?" to "Hallo, hoe gaat het met je?",
                "My name is Alex." to "Mijn naam is Alex."
            )
            lang.contains("russian") || lang.contains("русский") -> listOf(
                "Hello, how are you?" to "Привет, как дела?",
                "My name is Alex." to "Меня зовут Алекс."
            )
            lang.contains("chinese") || lang.contains("中文") || lang.contains("汉语") -> listOf(
                "Hello, how are you?" to "你好，你好吗？",
                "My name is Alex." to "我的名字是亚历克斯。"
            )
            lang.contains("japanese") || lang.contains("日本語") -> listOf(
                "Hello, how are you?" to "こんにちは、お元気ですか？",
                "My name is Alex." to "私の名前はアレックスです。"
            )
            lang.contains("hindi") || lang.contains("हिन्दी") -> listOf(
                "Hello, how are you?" to "नमस्ते, आप कैसे हैं?",
                "My name is Alex." to "मेरा नाम एलेक्स है।"
            )
            else -> emptyList()
        }
    }

    private fun getProofreadFewShot(languageTag: String): List<Pair<String, String>> {
        val lang = languageTag.lowercase()
        return when {
            lang.startsWith("en") -> emptyList() // English example already included
            lang.startsWith("fr") -> listOf(
                "je sui content de te voire" to "Je suis content de te voir."
            )
            lang.startsWith("es") -> listOf(
                "hola como estas tu vien" to "Hola, ¿cómo estás? Bien."
            )
            lang.startsWith("de") -> listOf(
                "ich habe ein grose Haus" to "Ich habe ein großes Haus."
            )
            lang.startsWith("it") -> listOf(
                "io sono molto contento di vederte" to "Io sono molto contento di vederti."
            )
            lang.startsWith("pt") -> listOf(
                "eu estou muito felis hoje" to "Eu estou muito feliz hoje."
            )
            lang.startsWith("nl") -> listOf(
                "ik ben heel blei om je te zien" to "Ik ben heel blij om je te zien."
            )
            lang.startsWith("ru") -> listOf(
                "привет как дила у тебя" to "Привет, как дела у тебя?"
            )
            lang.startsWith("tr") -> listOf(
                "ben bugün çok mutluyım" to "Ben bugün çok mutluyum."
            )
            lang.startsWith("pl") -> listOf(
                "jestem bardzo szczesliwy dzisiaj" to "Jestem bardzo szczęśliwy dzisiaj."
            )
            lang.startsWith("hi") -> listOf(
                "मैं बहुत खुस हूं आज" to "मैं बहुत खुश हूं आज।"
            )
            lang.startsWith("ar") -> listOf(
                "انا سعيد جدا اليوم" to "أنا سعيد جداً اليوم."
            )
            lang.startsWith("ja") -> listOf(
                "きょう は とても いい てんき です" to "今日はとてもいい天気です。"
            )
            lang.startsWith("zh") -> listOf(
                "我今天很高心" to "我今天很高兴。"
            )
            lang.startsWith("ko") -> listOf(
                "오늘 날씨가 너무 조아요" to "오늘 날씨가 너무 좋아요."
            )
            else -> emptyList()
        }
    }

    class ProofreadException(message: String) : Exception(message)
    class TranslateException(message: String) : Exception(message)

    companion object {
        private const val TAG = "LlamaProofreadService"
        private const val KEY_MODEL_PATH = "offline_model_path"
        private const val KEY_DECODER_PATH = "offline_decoder_path"
        private const val KEY_TOKENIZER_PATH = "offline_tokenizer_path"
        val AVAILABLE_MODELS = listOf("GGUF Model (Local)")
    }
}
