// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.keyboard.KeyboardSwitcher

/**
 * Stub ProofreadHelper for OfflineLite flavor.
 * No AI capabilities.
 */
object ProofreadHelper {
    private val mainHandler = Handler(Looper.getMainLooper())
    
    @JvmStatic
    val isOperationInProgress: Boolean = false
    
    @JvmStatic
    var lastOriginalText: String? = null
        private set
    
    @JvmStatic
    fun cancelCurrentOperation() { /* No-op */ }
    
    // Callback interface
    interface ProofreadCallback {
        fun onSuccess(proofreadText: String)
        fun onError(errorMessage: String)
    }

    @JvmStatic
    fun proofreadAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        showNotSupportedToast()
    }
    
    @JvmStatic
    fun proofreadAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        callback: ProofreadCallback
    ) {
        showNotSupportedToast()
    }

    @JvmStatic
    fun translateAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        showNotSupportedToast()
    }
    
    @JvmStatic
    fun translateAsync(
        context: Context,
        text: String,
        hasSelection: Boolean,
        callback: ProofreadCallback
    ) {
        showNotSupportedToast()
    }

    private fun showNotSupportedToast() {
        mainHandler.post {
            KeyboardSwitcher.getInstance().showToast("Not available in Lite version", false)
        }
    }
}
