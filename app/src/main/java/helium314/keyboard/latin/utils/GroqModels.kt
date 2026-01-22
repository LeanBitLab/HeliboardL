// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

object GroqModels {
    val AVAILABLE_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "qwen/qwen3-32b",
        "openai/gpt-oss-120b",
        "groq/compound-mini",
        "canopylabs/orpheus-v1-english"
    )
    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
}
