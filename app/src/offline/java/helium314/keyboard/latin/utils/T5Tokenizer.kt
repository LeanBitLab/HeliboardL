// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream

/**
 * Simple T5 tokenizer for grammar correction.
 * Uses a pre-built vocabulary file (spiece.vocab or tokenizer.json).
 * 
 * T5 uses SentencePiece under the hood with the following special tokens:
 * - <pad> = 0
 * - </s> = 1 (EOS)
 * - <unk> = 2
 * 
 * For grammar correction, the input format is:
 * "grammar: <text>"
 */
class T5Tokenizer(private val context: Context) {
    
    companion object {
        private const val TAG = "T5Tokenizer"
        private const val PAD_TOKEN_ID = 0
        private const val EOS_TOKEN_ID = 1
        private const val UNK_TOKEN_ID = 2
        
        // Common subword pieces for basic English text
        // This is a minimal fallback vocabulary - for production, load from file
        private val BASIC_VOCAB = mapOf(
            "\u2581" to 3,     // Space prefix (SentencePiece)
            "." to 4,
            "," to 5,
            "!" to 6,
            "?" to 7,
            "'" to 8,
            "\"" to 9,
            "-" to 10,
            ":" to 11,
            ";" to 12,
        )
    }
    
    private var vocabToId: Map<String, Int> = emptyMap()
    private var idToVocab: Map<Int, String> = emptyMap()
    private var isLoaded = false
    
    /**
     * Load vocabulary from a tokenizer.json or vocab file.
     * Falls back to basic vocabulary if file not found.
     */
    fun loadVocab(vocabFile: File?): Boolean {
        if (vocabFile == null || !vocabFile.exists()) {
            Log.w(TAG, "Vocab file not found, using basic fallback")
            return false
        }
        
        return try {
            val content = vocabFile.readText()
            when {
                vocabFile.name.endsWith(".json") -> loadFromJson(content)
                else -> loadFromTsv(content)
            }
            isLoaded = vocabToId.isNotEmpty()
            Log.d(TAG, "Loaded ${vocabToId.size} tokens")
            isLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab", e)
            false
        }
    }
    
    private fun loadFromJson(content: String) {
        // HuggingFace tokenizer.json format
        val json = JSONObject(content)
        val model = json.optJSONObject("model") ?: return
        
        val tempVocab = mutableMapOf<String, Int>()
        
        // Handle both Map (WordPiece/BPE) and Array (Unigram) formats
        val vocabObj = model.optJSONObject("vocab")
        if (vocabObj != null) {
             val keys = vocabObj.keys()
             while (keys.hasNext()) {
                 val token = keys.next()
                 tempVocab[token] = vocabObj.getInt(token)
             }
        } else {
            val vocabArray = model.optJSONArray("vocab")
            if (vocabArray != null) {
                for (i in 0 until vocabArray.length()) {
                    val entry = vocabArray.optJSONArray(i)
                    if (entry != null && entry.length() > 0) {
                        val token = entry.getString(0)
                        tempVocab[token] = i
                    }
                }
            } else {
                return
            }
        }
        
        vocabToId = tempVocab
        idToVocab = tempVocab.entries.associate { (k, v) -> v to k }
    }
    
    private fun loadFromTsv(content: String) {
        // Simple TSV format: token\tid
        val tempVocab = mutableMapOf<String, Int>()
        content.lines().forEachIndexed { index, line ->
            val parts = line.split("\t")
            if (parts.isNotEmpty()) {
                val token = parts[0]
                val id = parts.getOrNull(1)?.toIntOrNull() ?: index
                tempVocab[token] = id
            }
        }
        
        vocabToId = tempVocab
        idToVocab = tempVocab.entries.associate { (k, v) -> v to k }
    }
    
    /**
     * Simple character-level tokenization as fallback.
     * For production, this should use proper SentencePiece.
     */
    fun encode(text: String, addPrefix: Boolean = true): LongArray {
        val tokens = mutableListOf<Long>()
        
        // T5 grammar correction prefix
        val processedText = if (addPrefix) "grammar: $text" else text
        
        if (isLoaded && vocabToId.isNotEmpty()) {
            // Use loaded vocabulary with greedy tokenization
            var remaining = processedText.replace(" ", "\u2581")
            
            while (remaining.isNotEmpty()) {
                // Find longest matching token
                var matched = false
                for (len in minOf(remaining.length, 50) downTo 1) {
                    val candidate = remaining.substring(0, len)
                    val id = vocabToId[candidate]
                    if (id != null) {
                        tokens.add(id.toLong())
                        remaining = remaining.substring(len)
                        matched = true
                        break
                    }
                }
                
                if (!matched) {
                    // Unknown token - use UNK and skip one character
                    tokens.add(UNK_TOKEN_ID.toLong())
                    remaining = remaining.substring(1)
                }
            }
        } else {
            // Fallback: simple character tokenization with offset
            // This maps characters to token IDs (ASCII-based)
            processedText.forEach { char ->
                val id = when (char) {
                    ' ' -> 3  // Space becomes \u2581
                    else -> char.code + 100  // Offset to avoid special tokens
                }
                tokens.add(id.toLong())
            }
        }
        
        // Add EOS token
        tokens.add(EOS_TOKEN_ID.toLong())
        
        return tokens.toLongArray()
    }
    
    /**
     * Decode token IDs back to text.
     */
    fun decode(tokenIds: LongArray): String {
        val result = StringBuilder()
        
        for (id in tokenIds) {
            when (id.toInt()) {
                PAD_TOKEN_ID, EOS_TOKEN_ID -> break  // Stop at EOS or PAD
                UNK_TOKEN_ID -> result.append("?")
                else -> {
                    if (isLoaded && idToVocab.isNotEmpty()) {
                        val token = idToVocab[id.toInt()] ?: "?"
                        result.append(token.replace("\u2581", " "))
                    } else {
                        // Fallback: reverse ASCII offset
                        if (id == 3L) {
                            result.append(" ")
                        } else if (id > 100) {
                            result.append((id - 100).toInt().toChar())
                        }
                    }
                }
            }
        }
        
        return result.toString().trim()
    }
    
    fun getEosTokenId(): Long = EOS_TOKEN_ID.toLong()
    fun getPadTokenId(): Long = PAD_TOKEN_ID.toLong()
}
