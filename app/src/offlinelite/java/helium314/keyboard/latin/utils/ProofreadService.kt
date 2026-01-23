// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context

/**
 * Stub ProofreadService for OfflineLite flavor.
 * No AI capabilities to minimize APK size.
 */
class ProofreadService(private val context: Context) {

    enum class AIProvider {
        GEMINI, GROQ, OPENAI
    }

    // Always returns GEMINI as default, but methods do nothing
    fun getProvider(): AIProvider = AIProvider.GEMINI
    fun setProvider(provider: AIProvider) { /* No-op */ }

    fun getApiKey(): String? = null
    fun setApiKey(apiKey: String?) { /* No-op */ }
    fun hasApiKey(): Boolean = false

    fun getModelPath(): String? = null
    fun setModelPath(path: String?) { /* No-op */ }
    fun unloadModel() { /* No-op */ }
    fun getSystemPrompt(): String = ""
    fun setSystemPrompt(prompt: String) { /* No-op */ }
    fun getDecoderPath(): String? = null
    fun setDecoderPath(path: String?) { /* No-op */ }
    fun getTokenizerPath(): String? = null
    fun setTokenizerPath(path: String?) { /* No-op */ }

    fun getModelName(): String = "Lite Mode"
    fun setModelName(modelName: String) { /* No-op */ }

    fun getTargetLanguage(): String = "English"
    fun setTargetLanguage(language: String) { /* No-op */ }

    fun getHuggingFaceToken(): String? = null
    fun setHuggingFaceToken(token: String?) { /* No-op */ }

    fun getHuggingFaceModel(): String = "Lite Mode"
    fun setHuggingFaceModel(model: String) { /* No-op */ }

    fun getHuggingFaceEndpoint(): String = ""
    fun setHuggingFaceEndpoint(endpoint: String) { /* No-op */ }

    fun getGroqToken(): String? = null
    fun setGroqToken(token: String?) { /* No-op */ }

    fun getGroqModel(): String = "Lite Mode"
    fun setGroqModel(model: String) { /* No-op */ }

    suspend fun testApiKey(): Result<String> = Result.failure(Exception("Not supported in Lite version"))

    suspend fun proofread(text: String): Result<String> = Result.failure(Exception("Not supported in Lite version"))

    suspend fun translate(text: String): Result<String> = Result.failure(Exception("Not supported in Lite version"))

    companion object {
        val AVAILABLE_MODELS = emptyList<String>()
    }
}
