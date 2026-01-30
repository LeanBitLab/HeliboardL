/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsWithoutKey
import kotlinx.coroutines.flow.MutableStateFlow

// Shared state for provider selection
private val providerState = MutableStateFlow<String?>(null)

@Composable
fun AIIntegrationScreen(
    onClickBack: () -> Unit,
) {
    // Hide AI settings completely in offlinelite flavor
    if (BuildConfig.FLAVOR == "offlinelite") {
        onClickBack()
        return
    }
    
    if (BuildConfig.FLAVOR == "standard") {
        StandardAIIntegrationScreen(onClickBack)
    } else {
        OfflineAIIntegrationScreen(onClickBack)
    }
}

@Composable
private fun StandardAIIntegrationScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val service = helium314.keyboard.latin.utils.ProofreadService(ctx)
    
    // Initialize provider state if needed
    if (providerState.value == null) {
        providerState.value = service.getProvider().name
    }
    
    val currentProvider by providerState.collectAsState()
    
    val items = buildList {
        // Always show provider selection
        add(SettingsWithoutKey.AI_PROVIDER)
        
        // ADDED: Custom AI Keys entry
        if (BuildConfig.FLAVOR == "standard") {
            add(SettingsWithoutKey.CUSTOM_AI_KEYS)
        }
        
        // Show settings based on selected provider
        when (currentProvider) {
            "GROQ" -> {
                add(SettingsWithoutKey.GROQ_TOKEN)
                add(SettingsWithoutKey.GROQ_MODEL)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
            }
            "GEMINI" -> {
                add(SettingsWithoutKey.GEMINI_API_KEY)
                add(SettingsWithoutKey.GEMINI_MODEL)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
            }
            "OPENAI" -> {
                add(SettingsWithoutKey.HUGGINGFACE_TOKEN)
                add(SettingsWithoutKey.HUGGINGFACE_MODEL)
                add(SettingsWithoutKey.HUGGINGFACE_ENDPOINT)
                add(SettingsWithoutKey.GEMINI_TARGET_LANGUAGE)
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
        SettingsWithoutKey.OFFLINE_MODEL_PATH,
        SettingsWithoutKey.OFFLINE_KEEP_MODEL_LOADED
    )
    
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai_integration),
        settings = items
    )
}

// Update provider state when changed
fun updateProviderState(provider: String) {
    providerState.value = provider
}
