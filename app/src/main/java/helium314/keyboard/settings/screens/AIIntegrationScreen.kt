/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsWithoutKey

@Composable
fun AIIntegrationScreen(
    onClickBack: () -> Unit,
) {
    // Hide AI settings completely in offlinelite flavor
    if (BuildConfig.FLAVOR == "offlinelite") {
        onClickBack()
        return
    }

    if (BuildConfig.FLAVOR == "standard" || BuildConfig.FLAVOR == "standardfull") {
        StandardAIIntegrationScreen(onClickBack)
    } else {
        OfflineAIIntegrationScreen(onClickBack)
    }
}

@Composable
private fun StandardAIIntegrationScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    // Use remember to avoid re-creating the service on every recomposition
    val service = remember(ctx) { helium314.keyboard.latin.utils.ProofreadService(ctx) }

    var provider by remember { mutableStateOf(service.getProvider().name) }

    androidx.compose.runtime.DisposableEffect(service) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "ai_provider") {
                provider = service.getProvider().name
            }
        }
        val prefs = service.getPrefs()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val items = buildList {
        // Always show provider selection
        add(SettingsWithoutKey.AI_PROVIDER)
        add(SettingsWithoutKey.TRANSLATION_ENGINE)
        add(SettingsWithoutKey.AI_NEXT_WORD)
        // Custom AI Keys are only shown in the standard flavor (guaranteed by caller)
        add(SettingsWithoutKey.CUSTOM_AI_KEYS)

        // Show settings based on selected provider
        when (provider) {
            "GROQ" -> {
                add(SettingsWithoutKey.GROQ_TOKEN)
                add(SettingsWithoutKey.GROQ_MODEL)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
                add(SettingsWithoutKey.TRANSLATE_GROQ_MODEL)
            }
            "GEMINI" -> {
                add(SettingsWithoutKey.GEMINI_API_KEY)
                add(SettingsWithoutKey.GEMINI_MODEL)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
                add(SettingsWithoutKey.TRANSLATE_GEMINI_MODEL)
            }
            "OPENAI" -> {
                add(SettingsWithoutKey.HUGGINGFACE_TOKEN)
                add(SettingsWithoutKey.HUGGINGFACE_MODEL)
                add(SettingsWithoutKey.HUGGINGFACE_ENDPOINT)
                add(SettingsWithoutKey.AI_ALLOW_INSECURE_CONNECTIONS)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
                add(SettingsWithoutKey.TRANSLATE_HUGGINGFACE_MODEL)
            }
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_integration),
        settings = items
    )
}

@Composable
private fun OfflineAIIntegrationScreen(onClickBack: () -> Unit) {
    val items = listOf(
        SettingsWithoutKey.CUSTOM_AI_KEYS,
        SettingsWithoutKey.OFFLINE_MODEL_PATH,
        SettingsWithoutKey.OFFLINE_KEEP_MODEL_LOADED,
        SettingsWithoutKey.AI_NEXT_WORD
    )
    
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_integration),
        settings = items
    )
}
