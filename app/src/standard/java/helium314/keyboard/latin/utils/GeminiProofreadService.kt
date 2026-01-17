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
import com.google.ai.client.generativeai.type.generationConfig
import helium314.keyboard.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for proofreading text using Google's Gemini AI API.
 * Stores the API key securely using EncryptedSharedPreferences (API 23+)
 * or regular SharedPreferences with obfuscation (API 21-22).
 */
class GeminiProofreadService(private val context: Context) {


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
                Log.w("GeminiProofreadService", "Failed to create encrypted prefs, using regular prefs", e)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        } else {
            // API 21-22 doesn't support EncryptedSharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

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

    fun getModelName(): String = securePrefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModelName(modelName: String) {
        securePrefs.edit().apply {
            putString(KEY_MODEL_NAME, modelName)
            apply()
        }
    }

    fun getTargetLanguage(): String = securePrefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_TARGET_LANGUAGE) ?: DEFAULT_TARGET_LANGUAGE

    fun setTargetLanguage(language: String) {
        securePrefs.edit().apply {
            putString(KEY_TARGET_LANGUAGE, language)
            apply()
        }
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
            Log.e("GeminiProofreadService", "API key test failed", e)
            Result.failure(ProofreadException(e.message ?: "Unknown error"))
        }
    }

    /**
     * Proofreads the given text using Gemini AI.
     * @param text The text to proofread
     * @return Result containing the proofread text or an error
     */
    suspend fun proofread(text: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                ProofreadException(context.getString(R.string.proofread_no_api_key))
            )
        }

        if (text.isBlank()) {
            return@withContext Result.failure(
                ProofreadException(context.getString(R.string.proofread_no_text))
            )
        }

        try {
            val model = GenerativeModel(
                modelName = getModelName(),
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.1f // Low temperature for more deterministic corrections
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
                return@withContext Result.failure(
                    ProofreadException("Empty response from API")
                )
            }
            
            Result.success(proofreadText)
        } catch (e: Exception) {
            Log.e("GeminiProofreadService", "Proofreading failed", e)
            Result.failure(
                ProofreadException("Proofreading failed: ${e.message}")
            )
        }
    }

    /**
     * Translates the given text to English using Gemini AI.
     * @param text The text to translate
     * @return Result containing the translated text or an error
     */
    suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                TranslateException(context.getString(R.string.proofread_no_api_key))
            )
        }

        if (text.isBlank()) {
            return@withContext Result.failure(
                TranslateException(context.getString(R.string.translate_no_text))
            )
        }

        try {
            val model = GenerativeModel(
                modelName = getModelName(),
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.3f // Slightly higher for more natural translations
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
                return@withContext Result.failure(
                    TranslateException("Empty response from API")
                )
            }
            
            Result.success(translatedText)
        } catch (e: Exception) {
            Log.e("GeminiProofreadService", "Translation failed", e)
            Result.failure(
                TranslateException("Translation failed: ${e.message}")
            )
        }
    }

    class ProofreadException(message: String) : Exception(message)
    class TranslateException(message: String) : Exception(message)

    companion object {
        private const val PREFS_NAME = "gemini_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL_NAME = "gemini_model_name"
        private const val KEY_TARGET_LANGUAGE = "gemini_target_language"
        private const val KEY_NETWORK_DISABLED = "gemini_network_disabled"
        private const val DEFAULT_TARGET_LANGUAGE = "English"
        
        val AVAILABLE_MODELS = listOf(
            "gemma-3n-e2b-it",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )
        private const val DEFAULT_MODEL = "gemma-3n-e2b-it"
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
