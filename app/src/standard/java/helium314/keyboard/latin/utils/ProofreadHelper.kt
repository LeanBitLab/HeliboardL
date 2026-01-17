// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper class to handle proofreading async operations from Java code.
 * This avoids the complexity of Java-Kotlin coroutine interop.
 */
object ProofreadHelper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Store original text for potential undo
    @JvmStatic
    var lastOriginalText: String? = null
        private set
    
    private fun performAsyncOperation(
        context: Context,
        text: String,
        noTextErrorResId: Int,
        errorResId: Int,
        apiCall: suspend (GeminiProofreadService) -> Result<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val geminiService = GeminiProofreadService(context)

        // Check if API key is configured
        if (!geminiService.hasApiKey()) {
            Log.w("ProofreadHelper", "No API key configured")
            mainHandler.post {
                KeyboardSwitcher.getInstance().showToast(
                    context.getString(R.string.proofread_no_api_key),
                    true
                )
            }
            return
        }

        if (text.isBlank()) {
            Log.w("ProofreadHelper", "Text is blank")
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
        Log.i("ProofreadHelper", "Showing loading animation and starting API call")
        mainHandler.post {
            KeyboardSwitcher.getInstance().showLoadingAnimation()
        }

        // Launch coroutine for API call
        scope.launch {
            val result = apiCall(geminiService)
            Log.i("ProofreadHelper", "API call completed, success: ${result.isSuccess}")

            mainHandler.post {
                // Hide loading animation
                KeyboardSwitcher.getInstance().hideLoadingAnimation()

                result.fold(
                    onSuccess = { resultText ->
                        Log.i("ProofreadHelper", "Calling onSuccess callback")
                        onSuccess(resultText)
                    },
                    onFailure = { error ->
                        Log.e("ProofreadHelper", "API error: ${error.message}", error)
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
        Log.i("ProofreadHelper", "proofreadAsync called")
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
        Log.i("ProofreadHelper", "translateAsync called")
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
}
