// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import helium314.keyboard.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for proofreading text using Google's Gemini AI API or HuggingFace Inference API.
 * Stores the API key securely using EncryptedSharedPreferences (API 23+)
 * or regular SharedPreferences with obfuscation (API 21-22).
 */
class ProofreadService(private val context: Context) {

    enum class AIProvider {
        GEMINI, GROQ, OPENAI
    }

    private val securePrefs: SharedPreferences by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Fallback to regular prefs if encryption fails
                Log.w("ProofreadService", "Failed to create encrypted prefs, using regular prefs", e)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        } else {
            // API 21-22 doesn't support EncryptedSharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Provider selection
    fun getProvider(): AIProvider {
        val providerStr = securePrefs.getString(KEY_PROVIDER, AIProvider.GEMINI.name)
        return try {
            AIProvider.valueOf(providerStr ?: AIProvider.GEMINI.name)
        } catch (e: IllegalArgumentException) {
            AIProvider.GEMINI
        }
    }

    fun setProvider(provider: AIProvider) {
        securePrefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    // Gemini API key
    fun getApiKey(): String? = securePrefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(apiKey: String?) {
        securePrefs.edit().apply {
            if (apiKey.isNullOrBlank()) {
                remove(KEY_API_KEY)
            } else {
                putString(KEY_API_KEY, apiKey.trim())
            }
            apply()
        }
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    // Offline compatibility methods
    fun getModelPath(): String? = null
    fun setModelPath(path: String?) { /* No-op for standard flavor */ }
    fun unloadModel() { /* No-op */ }
    fun getSystemPrompt(): String = "Fix grammar and spelling"
    fun setSystemPrompt(prompt: String) { /* No-op */ }
    fun getDecoderPath(): String? = null
    fun setDecoderPath(path: String?) { /* No-op */ }
    fun getTokenizerPath(): String? = null
    fun setTokenizerPath(path: String?) { /* No-op */ }

    // Gemini model
    fun getModelName(): String = securePrefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModelName(modelName: String) {
        securePrefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
    }

    // Target language
    fun getTargetLanguage(): String = securePrefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE

    fun setTargetLanguage(language: String) {
        securePrefs.edit().putString(KEY_TARGET_LANGUAGE, language).apply()
    }

    // HuggingFace token (optional)
    fun getHuggingFaceToken(): String? = securePrefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setHuggingFaceToken(token: String?) {
        securePrefs.edit().apply {
            if (token.isNullOrBlank()) {
                remove(KEY_HF_TOKEN)
            } else {
                putString(KEY_HF_TOKEN, token.trim())
            }
            apply()
        }
    }

    // HuggingFace model name
    fun getHuggingFaceModel(): String = securePrefs.getString(KEY_HF_MODEL, DEFAULT_HF_MODEL) ?: DEFAULT_HF_MODEL

    fun setHuggingFaceModel(model: String) {
        securePrefs.edit().putString(KEY_HF_MODEL, model.trim()).apply()
    }

    // HuggingFace API endpoint
    fun getHuggingFaceEndpoint(): String {
        // Auto-select correct endpoint based on provider
        val defaultEndpoint = when (getProvider()) {
            AIProvider.GROQ -> GROQ_ENDPOINT
            else -> DEFAULT_HF_ENDPOINT
        }
        return securePrefs.getString(KEY_HF_ENDPOINT, defaultEndpoint) ?: defaultEndpoint
    }

    fun setHuggingFaceEndpoint(endpoint: String) {
        securePrefs.edit().putString(KEY_HF_ENDPOINT, endpoint.trim()).apply()
    }

    /**
     * Tests the API key by making a simple request.
     * @return Result with success message or error
     */
    suspend fun testApiKey(): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                ProofreadException(context.getString(R.string.proofread_no_api_key))
            )
        }

        try {
            val model = GenerativeModel(
                modelName = getModelName(),
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.1f
                    maxOutputTokens = 50
                }
            )
            
            val response = model.generateContent("Say 'OK' if you can read this.")
            val text = response.text?.trim()
            
            if (text.isNullOrBlank()) {
                Result.failure(ProofreadException("Empty response from API"))
            } else {
                Result.success(context.getString(R.string.gemini_api_key_valid))
            }
        } catch (e: Exception) {
            Log.e("ProofreadService", "API key test failed", e)
            Result.failure(ProofreadException(e.message ?: "Unknown error"))
        }
    }

    /**
     * Proofreads the given text using the selected AI provider.
     * @param text The text to proofread
     * @return Result containing the proofread text or an error
     */
    suspend fun proofread(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(
                ProofreadException(context.getString(R.string.proofread_no_text))
            )
        }

        when (getProvider()) {
            AIProvider.GEMINI -> geminiProofread(text)
            AIProvider.GROQ, AIProvider.OPENAI -> huggingFaceProofread(text)
        }
    }

    /**
     * Translates the given text using the selected AI provider.
     * @param text The text to translate
     * @return Result containing the translated text or an error
     */
    suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(
                TranslateException(context.getString(R.string.translate_no_text))
            )
        }

        when (getProvider()) {
            AIProvider.GEMINI -> geminiTranslate(text)
            AIProvider.GROQ, AIProvider.OPENAI -> huggingFaceTranslate(text)
        }
    }

    // ======================== Gemini Implementation ========================

    private suspend fun geminiProofread(text: String): Result<String> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                ProofreadException(context.getString(R.string.proofread_no_api_key))
            )
        }

        return try {
            val model = GenerativeModel(
                modelName = getModelName(),
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.1f
                    topK = 1
                    topP = 0.95f
                    maxOutputTokens = 8192
                },
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                )
            )

            val response = model.generateContent(PROOFREAD_PROMPT + text)
            val proofreadText = response.text?.trim()
            
            if (proofreadText.isNullOrBlank()) {
                Result.failure(ProofreadException("Empty response from API"))
            } else {
                Result.success(proofreadText)
            }
        } catch (e: Exception) {
            Log.e("ProofreadService", "Gemini proofreading failed", e)
            Result.failure(ProofreadException("Proofreading failed: ${e.message}"))
        }
    }

    private suspend fun geminiTranslate(text: String): Result<String> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                TranslateException(context.getString(R.string.proofread_no_api_key))
            )
        }

        return try {
            val model = GenerativeModel(
                modelName = getModelName(),
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.3f
                    topK = 1
                    topP = 0.95f
                    maxOutputTokens = 8192
                },
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                )
            )

            val targetLanguage = getTargetLanguage()
            val response = model.generateContent(getTranslatePrompt(targetLanguage) + text)
            val translatedText = response.text?.trim()
            
            if (translatedText.isNullOrBlank()) {
                Result.failure(TranslateException("Empty response from API"))
            } else {
                Result.success(translatedText)
            }
        } catch (e: Exception) {
            Log.e("ProofreadService", "Gemini translation failed", e)
            Result.failure(TranslateException("Translation failed: ${e.message}"))
        }
    }

    // ======================== HuggingFace/Groq Implementation ========================

    private fun huggingFaceRequest(prompt: String): Result<String> {
        val modelName = getHuggingFaceModel()
        if (modelName.isBlank()) {
            return Result.failure(
                ProofreadException(context.getString(R.string.huggingface_no_model))
            )
        }

        val token = getHuggingFaceToken()
        if (token == null) {
            return Result.failure(
                ProofreadException(context.getString(R.string.huggingface_no_token))
            )
        }

        val url = URL(getHuggingFaceEndpoint())
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            // Build OpenAI-compatible request body (works with Groq, OpenRouter, etc.)
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            
            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("temperature", 0.1)
                put("max_tokens", 512)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                parseOpenAIResponse(response)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
                Result.failure(ProofreadException("API error ($responseCode): $errorBody"))
            }
        } catch (e: Exception) {
            Log.e("ProofreadService", "Request failed", e)
            Result.failure(ProofreadException("Request failed: ${e.message}"))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOpenAIResponse(response: String): Result<String> {
        return try {
            // OpenAI-compatible format: {"choices": [{"message": {"content": "..."}}]}
            val jsonObject = JSONObject(response)
            val choices = jsonObject.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val content = message?.optString("content", "") ?: ""
                if (content.isNotBlank()) {
                    Result.success(content.trim())
                } else {
                    Result.failure(ProofreadException("Empty response from API"))
                }
            } else {
                Result.failure(ProofreadException("Invalid API response format"))
            }
        } catch (e: Exception) {
            Log.e("ProofreadService", "Failed to parse response: $response", e)
            Result.failure(ProofreadException("Failed to parse response: ${e.message}"))
        }
    }

    private fun huggingFaceProofread(text: String): Result<String> {
        val prompt = "$PROOFREAD_PROMPT$text"
        return huggingFaceRequest(prompt)
    }

    private fun huggingFaceTranslate(text: String): Result<String> {
        val targetLanguage = getTargetLanguage()
        val prompt = "${getTranslatePrompt(targetLanguage)}$text"
        return huggingFaceRequest(prompt)
    }

    class ProofreadException(message: String) : Exception(message)
    class TranslateException(message: String) : Exception(message)

    companion object {
        private const val PREFS_NAME = "gemini_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL_NAME = "gemini_model_name"
        private const val KEY_TARGET_LANGUAGE = "gemini_target_language"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_HF_TOKEN = "huggingface_token"
        private const val KEY_HF_MODEL = "huggingface_model"
        private const val KEY_HF_ENDPOINT = "huggingface_endpoint"
        private const val DEFAULT_TARGET_LANGUAGE = "English"
        private const val DEFAULT_HF_MODEL = "gpt-4o-mini"
        private const val DEFAULT_HF_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        
        val AVAILABLE_MODELS = listOf(
            "gemini-flash-latest",
            "gemma-3-27b-it",
            "gemma-3n-e4b-it",
            "gemma-3n-e2b-it"
        )
        private const val DEFAULT_MODEL = "gemini-flash-latest"
        private const val PROOFREAD_PROMPT = "Fix the grammar and spelling of the following text. " +
            "Maintain the original language and tone. " +
            "Return ONLY the corrected text, without quotes, explanations, or any additional text. " +
            "If the text is already correct, return it exactly as is. " +
            "Ensure the sentence structure is logical and coherent. " +
            "Text to proofread: "

        private fun getTranslatePrompt(targetLanguage: String) = """You are an expert translator. Translate the following text to $targetLanguage.

STRICT RULES:
1. Translate naturally and fluently - not word-for-word
2. Preserve the original meaning, tone, and intent
3. If the text is already in $targetLanguage, return it unchanged
4. Return ONLY the translated text with no explanations or notes
5. Preserve formatting, line breaks, and emojis
6. For names and proper nouns, keep them as-is unless there's a common equivalent in $targetLanguage

Text to translate:
"""
    }
}
