/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * Standard-flavor AI next-word engine factory. Uses the OpenAI-compatible chat-completion HTTP
 * shape already used by the standard proofreading service (endpoint / token / model come from
 * the same proofreading configuration) to produce a short next-word continuation. No network
 * access happens on the suggestion thread.
 */
object AINextWordEngineFactory {

    fun create(context: Context): AINextWordEngine? {
        if (!context.prefs().getBoolean(Settings.PREF_AI_NEXT_WORD, Defaults.PREF_AI_NEXT_WORD)) {
            return null
        }
        return StandardNextWordEngine(context.applicationContext)
    }
}

private class StandardNextWordEngine(private val context: Context) : AINextWordEngine {

    private val service: ProofreadService by lazy { ProofreadService(context) }

    override fun isReady(): Boolean {
        val provider = service.getProvider()
        return when (provider) {
            ProofreadService.AIProvider.GROQ -> !service.getGroqToken().isNullOrBlank() &&
                !service.getGroqModel().isBlank()
            ProofreadService.AIProvider.OPENAI -> !service.getHuggingFaceToken().isNullOrBlank() &&
                !service.getHuggingFaceModel().isBlank()
            else -> false
        }
    }

    override suspend fun suggestNextWords(prompt: String): List<String> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext emptyList()
        val isGroq = service.getProvider() == ProofreadService.AIProvider.GROQ
        val model = if (isGroq) service.getGroqModel() else service.getHuggingFaceModel()
        val token = if (isGroq) service.getGroqToken() else service.getHuggingFaceToken()
        val endpoint = service.getHuggingFaceEndpoint()
        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "LeanType/1.0")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", 0.2)
                    .put("max_tokens", 16)
                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext emptyList()
                }
                val content = parseContent(connection.inputStream.bufferedReader().use { it.readText() })
                splitCandidates(content)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseContent(response: String): String {
        return try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
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
