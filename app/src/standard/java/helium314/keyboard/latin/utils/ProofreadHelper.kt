/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Helper class to handle proofreading async operations from Java code.
 * This avoids the complexity of Java-Kotlin coroutine interop.
 */
object ProofreadHelper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Track current operation for cancellation
    private var currentJob: Job? = null
    
    // Check if an operation is in progress
    @JvmStatic
    val isOperationInProgress: Boolean
        get() = currentJob?.isActive == true
    
    // Store original text for potential undo
    @JvmStatic
    var lastOriginalText: String? = null
        private set
    
    /**
     * Cancel the current proofreading/translation operation if one is in progress.
     */
    @JvmStatic
    fun cancelCurrentOperation() {
        if (currentJob?.isActive == true) {
            currentJob?.cancel()
            currentJob = null
            mainHandler.post {
                KeyboardSwitcher.getInstance().hideLoadingAnimation()
                // Toast removed as visual feedback (stopping animation) is sufficient
            }
        }
    }
    
    private fun performAsyncOperation(
        context: Context,
        text: String,
        noTextErrorResId: Int,
        errorResId: Int,
        apiCall: suspend (ProofreadService) -> Result<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
        allowEmptyInput: Boolean = false
    ) {
        val service = ProofreadService(context)

        // Check if API key/token is configured based on provider
        val provider = service.getProvider()
        when (provider) {
            ProofreadService.AIProvider.GEMINI -> {
                if (!service.hasApiKey()) {
                    mainHandler.post {
                        KeyboardSwitcher.getInstance().showToast(
                            context.getString(R.string.proofread_no_api_key),
                            true
                        )
                    }
                    return
                }
            }
            ProofreadService.AIProvider.GROQ -> {
                if (service.getGroqToken() == null) {
                    mainHandler.post {
                        KeyboardSwitcher.getInstance().showToast(
                            context.getString(R.string.huggingface_no_token),
                            true
                        )
                    }
                    return
                }
            }
            ProofreadService.AIProvider.OPENAI -> {
                if (service.getHuggingFaceToken() == null) {
                    mainHandler.post {
                        KeyboardSwitcher.getInstance().showToast(
                            context.getString(R.string.huggingface_no_token),
                            true
                        )
                    }
                    return
                }
            }
        }

        if (!allowEmptyInput && text.isBlank()) {
            mainHandler.post {
                KeyboardSwitcher.getInstance().showToast(
                    context.getString(noTextErrorResId),
                    true
                )
            }
            return
        }

        // Store original text for undo
        lastOriginalText = text

        // Show loading animation on suggestion strip
        mainHandler.post {
            KeyboardSwitcher.getInstance().showLoadingAnimation()
        }

        // Launch coroutine for API call and track it for cancellation
        currentJob = scope.launch {
            val result = apiCall(service)

            mainHandler.post {
                currentJob = null
                // Hide loading animation
                KeyboardSwitcher.getInstance().hideLoadingAnimation()

                result.fold(
                    onSuccess = { resultText ->
                        onSuccess(resultText)
                    },
                    onFailure = { error ->
                        onError(error.message ?: "Unknown error")
                        KeyboardSwitcher.getInstance().showToast(
                            context.getString(errorResId, error.message ?: "Unknown error"),
                            false
                        )
                    }
                )
            }
        }
    }

    /**
     * Proofread text asynchronously and call the callback with the result.
     * 
     * @param context Application context
     * @param text Text to proofread
     * @param hasSelection Whether text was selected (false = entire field)
     * @param onSuccess Callback with proofread text
     * @param onError Callback with error message
     */
    @JvmStatic
    fun proofreadAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        performAsyncOperation(
            context = context,
            text = text,
            noTextErrorResId = R.string.proofread_no_text,
            errorResId = R.string.proofread_error,
            apiCall = { service -> service.proofread(text) },
            onSuccess = onSuccess,
            onError = onError
        )
    }
    
    /**
     * Simple Java-friendly interface for proofreading.
     */
    interface ProofreadCallback {
        fun onSuccess(proofreadText: String)
        fun onError(errorMessage: String)
    }
    
    /**
     * Java-friendly version using callback interface.
     */
    @JvmStatic
    fun proofreadAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        callback: ProofreadCallback
    ) {
        proofreadAsync(
            context = context,
            text = text,
            hasSelection = hasSelection,
            onSuccess = { callback.onSuccess(it) },
            onError = { callback.onError(it) }
        )
    }

    /**
     * Translate text asynchronously and call the callback with the result.
     * 
     * @param context Application context
     * @param text Text to translate
     * @param hasSelection Whether text was selected (false = entire field)
     * @param onSuccess Callback with translated text
     * @param onError Callback with error message
     */
    @JvmStatic
    fun translateAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        performAsyncOperation(
            context = context,
            text = text,
            noTextErrorResId = R.string.translate_no_text,
            errorResId = R.string.translate_error,
            apiCall = { service -> service.translate(text) },
            onSuccess = onSuccess,
            onError = onError
        )
    }
    
    /**
     * Simple Java-friendly interface for translation (reuses ProofreadCallback).
     */
    @JvmStatic
    fun translateAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        callback: ProofreadCallback
    ) {
        translateAsync(
            context = context,
            text = text,
            hasSelection = hasSelection,
            onSuccess = { callback.onSuccess(it) },
            onError = { callback.onError(it) }
        )
    }
    /**
     * Perform custom AI action asynchronously.
     */
    @JvmStatic
    fun customAsync(
        context: Context,
        text: String,
        prompt: String,
        hasSelection: Boolean,
        showThinking: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        performAsyncOperation(
            context = context,
            text = text,
            noTextErrorResId = R.string.proofread_no_text,
            errorResId = R.string.proofread_error,
            apiCall = { service -> service.proofread(text, overridePrompt = prompt, showThinking = showThinking) },
            onSuccess = onSuccess,
            onError = onError,
            allowEmptyInput = true
        )
    }

    /**
     * Java-friendly interface for custom action.
     */
    @JvmStatic
    fun customAsync(
        context: Context,
        text: String,
        prompt: String,
        hasSelection: Boolean,
        showThinking: Boolean = false,
        callback: ProofreadCallback
    ) {
        customAsync(
            context = context,
            text = text,
            prompt = prompt,
            hasSelection = hasSelection,
            showThinking = showThinking,
            onSuccess = { callback.onSuccess(it) },
            onError = { callback.onError(it) }
        )
    }
}
