// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context

/**
 * Stub stub implementation for Offline flavor.
 * No-op for all methods.
 */
class GeminiProofreadService(private val context: Context) {

    fun getApiKey(): String? = null
    fun setApiKey(apiKey: String?) {}
    fun hasApiKey(): Boolean = false
    fun getModelName(): String = "Offline Mode"
    fun setModelName(modelName: String) {}
    fun getTargetLanguage(): String = "None"
    fun setTargetLanguage(language: String) {}

    suspend fun testApiKey(): Result<String> = Result.failure(Exception("Offline Mode"))
    suspend fun proofread(text: String): Result<String> = Result.failure(Exception("Offline Mode"))
    suspend fun translate(text: String): Result<String> = Result.failure(Exception("Offline Mode"))

    companion object {
        val AVAILABLE_MODELS = emptyList<String>()
    }
}
