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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline proofreading service using ONNX Runtime with T5 grammar correction models.
 * 
 * T5 uses encoder-decoder architecture:
 * 1. Encoder: Processes input text → encoder hidden states
 * 2. Decoder: Uses hidden states to generate corrected text token by token
 * 
 * Expected model files:
 * - encoder_model_quant.onnx 
 * - init_decoder_quant.onnx (initial decoder)
 * - tokenizer.json (T5 vocabulary)
 */
class ProofreadService(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.prefs()
    }
    
    // Singleton holder for model state to prevent reloading on every request
    object ModelHolder {
        var ortEnvironment: OrtEnvironment? = null
        var encoderSession: OrtSession? = null
        var decoderSession: OrtSession? = null
        var currentEncoderPath: String? = null
        var currentDecoderPath: String? = null
        var tokenizer: T5Tokenizer? = null
        var isModelAvailable: Boolean = true
        private var modelDir: File? = null

        // Smart Unload Logic
        private var unloadJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO)
        private const val UNLOAD_DELAY_MS = 10 * 60 * 1000L // 10 minutes

        @Synchronized
        fun scheduleUnload(context: Context) { // Context required to check prefs
            unloadJob?.cancel()
            
            // Check preference
            val prefs = context.prefs()
            val keepLoaded = prefs.getBoolean(Settings.PREF_OFFLINE_KEEP_MODEL_LOADED, Defaults.PREF_OFFLINE_KEEP_MODEL_LOADED)
            
            if (keepLoaded) {
                 Log.i("OnnxProofreadService", "Model unload skipped (Keep Model Loaded enabled)")
                 return
            }

            unloadJob = scope.launch {
                delay(UNLOAD_DELAY_MS)
                unloadModel()
                Log.i("OnnxProofreadService", "Offline AI model unloaded due to inactivity")
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
                encoderSession?.close()
                decoderSession?.close()
                ortEnvironment?.close()
            } catch (e: Exception) {
                Log.w("OnnxProofreadService", "Error closing ONNX sessions", e)
            }
            encoderSession = null
            decoderSession = null
            ortEnvironment = null
            currentEncoderPath = null
            currentDecoderPath = null
            tokenizer = null
            isModelAvailable = true // Reset availability flag on unload
        }

        @Synchronized
        fun loadModel(
            context: Context,
            encoderPath: String,
            decoderPath: String?,
            tokenizerPath: String?
        ): Boolean {
            cancelUnload() // Cancel any pending unload since we are loading/using it

            // Check if already loaded with same paths
            if (encoderSession != null && currentEncoderPath == encoderPath &&
                (decoderPath.isNullOrBlank() || (decoderSession != null && currentDecoderPath == decoderPath))) {
                return true
            }

            unloadModel() // Ensure clean slate if paths changed

            return try {
                // Create model cache directory
                modelDir = File(context.cacheDir, "onnx_model")
                modelDir!!.mkdirs()

                // Initialize tokenizer
                tokenizer = T5Tokenizer(context)
                if (!tokenizerPath.isNullOrBlank()) {
                    val tokenizerFile = copyUriToCache(context, Uri.parse(tokenizerPath), "tokenizer.json", modelDir!!)
                    if (tokenizerFile != null) {
                        tokenizer!!.loadVocab(tokenizerFile)
                    }
                }

                // Initialize ONNX Runtime
                ortEnvironment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                }

                // Copy and load encoder
                val encoderFile = copyUriToCache(context, Uri.parse(encoderPath), "encoder.onnx", modelDir!!)
                if (encoderFile == null) {
                    Log.e("OnnxProofreadService", "Failed to copy encoder")
                    return false
                }

                encoderSession = ortEnvironment!!.createSession(encoderFile.absolutePath, sessionOptions)
                currentEncoderPath = encoderPath

                // Copy and load decoder if provided
                if (!decoderPath.isNullOrBlank()) {
                    val decoderFile = copyUriToCache(context, Uri.parse(decoderPath), "decoder.onnx", modelDir!!)
                    if (decoderFile != null) {
                        decoderSession = ortEnvironment!!.createSession(decoderFile.absolutePath, sessionOptions)
                        currentDecoderPath = decoderPath
                    }
                }
                
                isModelAvailable = true
                true
            } catch (e: Throwable) {
                Log.e("OnnxProofreadService", "Failed to load ONNX models", e)
                isModelAvailable = false
                false
            }
        }

        private fun copyUriToCache(context: Context, uri: Uri, targetName: String, dir: File): File? {
            val targetFile = File(dir, targetName)
            if (targetFile.exists() && targetFile.length() > 0) return targetFile
            
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.exists() && targetFile.length() > 0) targetFile else null
            } catch (e: Exception) {
                Log.e("OnnxProofreadService", "Failed to copy $targetName", e)
                null
            }
        }
    }

    // AI Provider support (API compatibility)
    enum class AIProvider {
        GEMINI, GROQ, OPENAI
    }
    
    fun getProvider(): AIProvider = AIProvider.GROQ
    fun setProvider(provider: AIProvider) { /* No-op */ }

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

    // Model management - encoder path
    fun getModelPath(): String? = prefs.getString(KEY_ENCODER_PATH, null)
    
    fun setModelPath(path: String?) {
        prefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_ENCODER_PATH)
            } else {
                putString(KEY_ENCODER_PATH, path)
            }
            apply()
        }
        ModelHolder.unloadModel()
    }

    // Decoder path (separate setting)
    fun getDecoderPath(): String? = prefs.getString(KEY_DECODER_PATH, null)
    
    fun setDecoderPath(path: String?) {
        prefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_DECODER_PATH)
            } else {
                putString(KEY_DECODER_PATH, path)
            }
            apply()
        }
        ModelHolder.unloadModel()
    }

    // Tokenizer path (vocabulary file)
    fun getTokenizerPath(): String? = prefs.getString(KEY_TOKENIZER_PATH, null)
    
    fun setTokenizerPath(path: String?) {
        prefs.edit().apply {
            if (path.isNullOrBlank()) {
                remove(KEY_TOKENIZER_PATH)
            } else {
                putString(KEY_TOKENIZER_PATH, path)
            }
            apply()
        }
        ModelHolder.unloadModel()
        ModelHolder.tokenizer = null
    }

    fun getSystemPrompt(): String = prefs.getString(Settings.PREF_OFFLINE_SYSTEM_PROMPT, "") ?: ""

    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(Settings.PREF_OFFLINE_SYSTEM_PROMPT, prompt).apply()
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

    fun unloadModel() {
        ModelHolder.unloadModel()
    }

    /**
     * Copy a content URI to cache and return the local file path.
     */


    /**
     * Run T5 encoder-decoder inference for grammar correction.
     */
    /**
     * Run T5 encoder-decoder inference for translation.
     */
    suspend fun translate(text: String): Result<String> {
        val target = prefs.getString(Settings.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE, Defaults.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE) ?: Defaults.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE
        // T5 standard prefix for translation
        val prompt = "translate English to $target: "
        return proofread(text, overridePrompt = prompt)
    }

    /**
     * Run T5 encoder-decoder inference.
     */
    suspend fun proofread(text: String, overridePrompt: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val encoderPath = getModelPath()
        if (encoderPath.isNullOrBlank()) {
            return@withContext Result.failure(ProofreadException("Model not loaded. Please select encoder ONNX file."))
        }

        // Load model (or get cached)
        if (!ModelHolder.loadModel(context, encoderPath, getDecoderPath(), getTokenizerPath())) {
             Log.e(TAG, "Model load failed")
             return@withContext Result.failure(ProofreadException("Failed to load model."))
        }

        // Cancel unload timer while working
        ModelHolder.cancelUnload()

        try {
            val maxTokens = prefs.getInt(Settings.PREF_OFFLINE_MAX_TOKENS, Defaults.PREF_OFFLINE_MAX_TOKENS)
            
            // 1. Tokenize input
            val prompt = overridePrompt ?: getSystemPrompt()
            val inputText = if (prompt.isNotBlank()) "$prompt$text" else text
            val inputIds = ModelHolder.tokenizer!!.encode(inputText, addPrefix = false)
            
            val batchSize = 1L
            val seqLen = inputIds.size.toLong()
            val inputShape = longArrayOf(batchSize, seqLen)
            
            // 2. Create input tensors
            val inputTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, LongBuffer.wrap(inputIds), inputShape)
            val attentionMask = LongArray(inputIds.size) { 1L }
            val attentionTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, LongBuffer.wrap(attentionMask), inputShape)
            
            // 3. Run encoder
            val encoderInputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to attentionTensor
            )
            
            
            val startTime = System.currentTimeMillis()
            val encoderResults = ModelHolder.encoderSession!!.run(encoderInputs)
            val encoderTime = System.currentTimeMillis() - startTime
            
            // Get encoder hidden states
            val encoderOutput = encoderResults[0]
            val hiddenStates = encoderOutput.value // [batch, seq, hidden_dim]
            
            // 4. Run decoder (if available)
            val outputText = if (ModelHolder.decoderSession != null && hiddenStates is Array<*>) {
                runDecoderLoop(hiddenStates, attentionMask, maxTokens)
            } else {
                Log.w(TAG, "Decoder not available, returning original text")
                text
            }
            
            // Clean up
            inputTensor.close()
            attentionTensor.close()
            encoderResults.close()
            
            // Schedule unload after work is done
            ModelHolder.scheduleUnload(context)

            // Strip prompt prefix if model echoed it back
            val cleanedOutput = if (prompt.isNotBlank() && outputText.startsWith(prompt, ignoreCase = true)) {
                outputText.removePrefix(prompt).trimStart()
            } else {
                outputText
            }
            
            if (cleanedOutput.isNotBlank()) {
                Result.success(cleanedOutput)
            } else {
                Result.success(text)
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Proofread failed", e)
            ModelHolder.scheduleUnload(context) // Ensure we still schedule unload on error
            Result.failure(ProofreadException(e.message ?: "Unknown error"))
        }
    }

    /**
     * Run decoder auto-regressively to generate output tokens.
     * Supports multiple T5 decoder variants:
     * - Basic decoder (input_ids, encoder_hidden_states, encoder_attention_mask)
     * - Decoder with past (adds past_key_values/pkv_* inputs)
     * - Merged decoder (adds use_cache_branch flag)
     */
    private fun runDecoderLoop(encoderHiddenStates: Array<*>, encoderAttentionMask: LongArray, maxTokens: Int): String {
        if (ModelHolder.decoderSession == null) return ""
        
        try {
            // Get hidden states as 3D array [batch, seq, hidden]
            @Suppress("UNCHECKED_CAST")
            val hiddenArray = encoderHiddenStates[0] as? Array<FloatArray> ?: return ""
            val seqLen = hiddenArray.size
            val hiddenDim = hiddenArray[0].size
            

            
            // Flatten hidden states for tensor
            val flatHidden = FloatArray(seqLen * hiddenDim)
            for (i in 0 until seqLen) {
                System.arraycopy(hiddenArray[i], 0, flatHidden, i * hiddenDim, hiddenDim)
            }
            
            // Create encoder_hidden_states tensor
            val hiddenShape = longArrayOf(1, seqLen.toLong(), hiddenDim.toLong())
            val hiddenTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, FloatBuffer.wrap(flatHidden), hiddenShape)
            
            // Create encoder attention mask tensor
            val attentionShape = longArrayOf(1, encoderAttentionMask.size.toLong())
            val attentionTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, LongBuffer.wrap(encoderAttentionMask), attentionShape)
            
            // Analyze decoder inputs to determine model type
            val inputNames = ModelHolder.decoderSession!!.inputNames.toList()
            val pkvInputNames = inputNames.filter { it.startsWith("past_key_values") || it.startsWith("pkv") }
            val useCacheBranchInput = inputNames.find { it == "use_cache_branch" }
            val numLayers = pkvInputNames.size / 4 // 4 tensors per layer (decoder key/value, encoder key/value)
            
            val hasPkvInputs = pkvInputNames.isNotEmpty()
            val isMergedDecoder = useCacheBranchInput != null
            

            
            // Start with decoder start token (pad_token = 0 for T5)
            val generatedTokens = mutableListOf<Long>(0L)
            val eosTokenId = ModelHolder.tokenizer!!.getEosTokenId()
            
            // KV-cache storage for decoders that output present.* tensors
            var pastKeyValues: Map<String, OnnxTensor>? = null
            
            val startTime = System.currentTimeMillis()
            
            for (step in 0 until maxTokens) {
                // For KV-cache models, only pass the last token after first step
                // CRITICAL FIX: Only use valid pastKeyValues if model actually accepts PKV inputs
                val inputTokens = if (step > 0 && pastKeyValues != null && hasPkvInputs) {
                    longArrayOf(generatedTokens.last())
                } else {
                    generatedTokens.toLongArray()
                }
                
                val decoderShape = longArrayOf(1, inputTokens.size.toLong())
                val decoderInputTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, LongBuffer.wrap(inputTokens), decoderShape)
                
                // Build decoder inputs
                val decoderInputs = mutableMapOf<String, OnnxTensor>()
                for (inputName in inputNames) {
                    when {
                        inputName.contains("input_ids") || inputName.contains("decoder_input_ids") -> 
                            decoderInputs[inputName] = decoderInputTensor
                        inputName.contains("encoder_hidden_states") || inputName.contains("hidden_states") -> 
                            decoderInputs[inputName] = hiddenTensor
                        inputName.contains("encoder_attention_mask") || inputName.contains("attention_mask") -> 
                            decoderInputs[inputName] = attentionTensor
                    }
                }
                
                // Handle use_cache_branch for merged decoders
                if (isMergedDecoder && useCacheBranchInput != null) {
                    val useCacheValue = step > 0 // false on first run, true after
                    val useCacheTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, booleanArrayOf(useCacheValue))
                    decoderInputs[useCacheBranchInput] = useCacheTensor
                }
                
                // Add past_key_values from previous step (if available and model expects them)
                if (hasPkvInputs && pastKeyValues != null) {
                    for ((name, tensor) in pastKeyValues!!) {
                        // Map present.X.* output names to past_key_values.X.* or pkv_* input names
                        val inputName = name.replace("present", "past_key_values")
                        if (inputNames.contains(inputName)) {
                            decoderInputs[inputName] = tensor
                        } else {
                            // Try pkv format (pkv_0, pkv_1, etc.)
                            val pkvMatch = pkvInputNames.find { it.endsWith(name.substringAfter("present.")) }
                            if (pkvMatch != null) {
                                decoderInputs[pkvMatch] = tensor
                            }
                        }
                    }
                } else if (hasPkvInputs && step == 0) {
                    // First step with PKV model: provide zero tensors
                    // T5 pkv format: pkv_0 to pkv_N where first half is decoder self-attn, second half is encoder cross-attn
                    // Shape: [batch, num_heads, seq_len, head_dim]
                    
                    var numHeads = 8L // Default T5-small
                    
                    // improved head detection from model metadata
                    try {
                        // Try to find the shape of the first PKV input
                        val pkvInfo = ModelHolder.decoderSession!!.inputInfo[pkvInputNames.first()]
                        val shape = pkvInfo?.info as? ai.onnxruntime.TensorInfo
                        if (shape != null) {
                            val dims = shape.shape
                            // Shape is usually [batch, heads, seq, dim] -> index 1
                            if (dims.size == 4 && dims[1] > 0) {
                                numHeads = dims[1]
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not detect numHeads from model info", e)
                    }

                    val headDim = hiddenDim.toLong() / numHeads
                    val numPkv = pkvInputNames.size
                    
                    for (pkvName in pkvInputNames) {
                        // Determine if this is encoder cross-attention or decoder self-attention
                        // pkv_0 to pkv_(N/2-1) = decoder self-attention (seq_len = 0 initially)
                        // pkv_(N/2) to pkv_(N-1) = encoder cross-attention (seq_len = encoder_seq_len)
                        val pkvIndex = pkvName.removePrefix("pkv_").removePrefix("past_key_values.").toIntOrNull() ?: 0
                        val isEncoderPkv = pkvIndex >= numPkv / 2 || pkvName.contains("encoder")
                        
                        val pkvSeqLen = if (isEncoderPkv) seqLen.toLong() else 0L
                        val pkvShape = longArrayOf(1, numHeads, pkvSeqLen, headDim)
                        val emptyPkv = FloatArray((1 * numHeads * pkvSeqLen * headDim).toInt())
                        val pkvTensor = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, FloatBuffer.wrap(emptyPkv), pkvShape)
                        decoderInputs[pkvName] = pkvTensor
                    }
                }
                

                
                // Run decoder step
                val decoderResults = ModelHolder.decoderSession!!.run(decoderInputs)
                
                // Get logits (usually first output)
                var logitsOutput: Any? = null
                val newPastKeyValues = mutableMapOf<String, OnnxTensor>()
                
                for (i in 0 until decoderResults.size()) {
                    val outputInfo = ModelHolder.decoderSession!!.outputNames.toList()[i]
                    val outputValue = decoderResults[i]
                    
                    when {
                        outputInfo == "logits" || i == 0 -> {
                            logitsOutput = outputValue.value
                        }
                        outputInfo.startsWith("present") -> {
                            // Save present.* outputs for next step
                            // Need to copy tensor data since result will be closed
                            val tensorValue = outputValue.value
                            if (tensorValue is Array<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val floatData = tensorValue as? Array<Array<Array<FloatArray>>>
                                if (floatData != null) {
                                    val batch = floatData.size
                                    val heads = floatData[0].size
                                    val seqL = floatData[0][0].size
                                    val dim = floatData[0][0][0].size
                                    val flat = FloatArray(batch * heads * seqL * dim)
                                    var idx = 0
                                    for (b in 0 until batch) {
                                        for (h in 0 until heads) {
                                            for (s in 0 until seqL) {
                                                System.arraycopy(floatData[b][h][s], 0, flat, idx, dim)
                                                idx += dim
                                            }
                                        }
                                    }
                                    val shape = longArrayOf(batch.toLong(), heads.toLong(), seqL.toLong(), dim.toLong())
                                    newPastKeyValues[outputInfo] = OnnxTensor.createTensor(ModelHolder.ortEnvironment!!, FloatBuffer.wrap(flat), shape)
                                }
                            }
                        }
                    }
                }
                
                val nextToken = getNextToken(logitsOutput, inputTokens.size.toLong() - 1)
                
                // Close previous PKV tensors and update with new ones
                pastKeyValues?.values?.forEach { it.close() }
                pastKeyValues = if (newPastKeyValues.isNotEmpty()) newPastKeyValues else null
                
                decoderInputTensor.close()
                decoderResults.close()
                
                // Check for EOS
                if (nextToken == eosTokenId) {
                    break
                }
                
                generatedTokens.add(nextToken)
            }
            
            val decoderTime = System.currentTimeMillis() - startTime
            
            // Clean up
            pastKeyValues?.values?.forEach { it.close() }
            hiddenTensor.close()
            attentionTensor.close()
            
            // Decode tokens (skip first token which is start token)
            val outputTokens = generatedTokens.drop(1).toLongArray()
            return ModelHolder.tokenizer!!.decode(outputTokens)
            
        } catch (e: Exception) {
            Log.e(TAG, "Decoder loop failed", e)
            return ""
        }
    }

    /**
     * Get next token from logits using greedy decoding.
     */
    private fun getNextToken(logits: Any?, position: Long): Long {
        val pos = position.toInt()
        return when (logits) {
            is Array<*> -> {
                // Shape: [batch, seq, vocab]
                @Suppress("UNCHECKED_CAST")
                val batchLogits = logits[0] as? Array<FloatArray>
                if (batchLogits != null && pos < batchLogits.size) {
                    val vocabLogits = batchLogits[pos]
                    // Argmax
                    vocabLogits.indices.maxByOrNull { vocabLogits[it] }?.toLong() ?: 0L
                } else 0L
            }
            is FloatArray -> {
                // Direct vocab logits
                logits.indices.maxByOrNull { logits[it] }?.toLong() ?: 0L
            }
            else -> {
                Log.w(TAG, "Unknown logits type: ${logits?.javaClass}")
                0L
            }
        }
    }



    class ProofreadException(message: String) : Exception(message)
    class TranslateException(message: String) : Exception(message)

    companion object {
        private const val TAG = "OnnxProofreadService"
        private const val KEY_ENCODER_PATH = "offline_model_path"
        private const val KEY_DECODER_PATH = "offline_decoder_path"
        private const val KEY_TOKENIZER_PATH = "offline_tokenizer_path"
        val AVAILABLE_MODELS = listOf("T5 Grammar Correction (ONNX)")
    }
}
