/*
 * Copyright (C) 2026 LeanBitLab
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.checkTimestampFormat
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.NextScreenIcon
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.Theme

import helium314.keyboard.settings.preferences.BackupRestorePreference
import helium314.keyboard.settings.preferences.LoadGestureLibPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.previewDark
import androidx.core.content.edit
import helium314.keyboard.settings.FeedbackManager
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text


@Composable
fun AdvancedSettingsScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val items = listOfNotNull(
        Settings.PREF_ALWAYS_INCOGNITO_MODE,
        Settings.PREF_DISABLE_NETWORK,
        Settings.PREF_KEY_LONGPRESS_TIMEOUT,
        if (Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SWIPE_SWITCH_LANGUAGE
            || Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SWIPE_SWITCH_LANGUAGE)
            Settings.PREF_LANGUAGE_SWIPE_DISTANCE else null,
        Settings.PREF_SPACE_TO_CHANGE_LANG,
        Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD,
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.PREF_SHOW_SETUP_WIZARD_ICON else null,
        Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        Settings.PREF_ABC_AFTER_EMOJI,
        Settings.PREF_ABC_AFTER_CLIP,
        Settings.PREF_CUSTOM_CURRENCY_KEY,
        Settings.PREF_MORE_POPUP_KEYS,
        Settings.PREF_TIMESTAMP_FORMAT,
        SettingsWithoutKey.BACKUP_RESTORE,
        if (BuildConfig.DEBUG || prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS))
            SettingsWithoutKey.DEBUG_SETTINGS else null,
        R.string.settings_category_experimental,
        Settings.PREF_EMOJI_MAX_SDK,
        Settings.PREF_URL_DETECTION,

    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_advanced),
        settings = items
    )
}

@SuppressLint("ApplySharedPref")
fun createAdvancedSettings(context: Context) = listOfNotNull(
    Setting(context, Settings.PREF_ALWAYS_INCOGNITO_MODE,
        R.string.incognito, R.string.prefs_force_incognito_mode_summary)
    {
        SwitchPreference(it, Defaults.PREF_ALWAYS_INCOGNITO_MODE) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEY_LONGPRESS_TIMEOUT, R.string.prefs_key_longpress_timeout_settings) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_LONGPRESS_TIMEOUT,
            range = 100f..700f,
            description = { stringResource(R.string.abbreviation_unit_milliseconds, it.toString()) }
        )
    },

    Setting(context, Settings.PREF_LANGUAGE_SWIPE_DISTANCE, R.string.prefs_language_swipe_distance) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_LANGUAGE_SWIPE_DISTANCE,
            range = 2f..18f,
            description = { it.toString() }
        )
    },

    Setting(context, Settings.PREF_SPACE_TO_CHANGE_LANG,
        R.string.prefs_long_press_keyboard_to_change_lang,
        R.string.prefs_long_press_keyboard_to_change_lang_summary)
    {
        SwitchPreference(it, Defaults.PREF_SPACE_TO_CHANGE_LANG)
    },
    Setting(context, Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, R.string.prefs_long_press_symbol_for_numpad) {
        SwitchPreference(it, Defaults.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD)
    },
    Setting(context, Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, R.string.prefs_enable_emoji_alt_physical_key,
        R.string.prefs_enable_emoji_alt_physical_key_summary)
    {
        SwitchPreference(it, Defaults.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
    },
    Setting(context, Settings.PREF_SHOW_SETUP_WIZARD_ICON, R.string.show_setup_wizard_icon, R.string.show_setup_wizard_icon_summary) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_SHOW_SETUP_WIZARD_ICON) { SystemBroadcastReceiver.toggleAppIcon(ctx) }
    },
    Setting(context, Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        R.string.switch_keyboard_after, R.string.after_symbol_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        R.string.switch_keyboard_after, R.string.after_numpad_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_EMOJI, R.string.switch_keyboard_after, R.string.after_emoji) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_EMOJI)
    },
    Setting(context, Settings.PREF_ABC_AFTER_CLIP, R.string.switch_keyboard_after, R.string.after_clip) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_EMOJI)
    },
    Setting(context, Settings.PREF_CUSTOM_CURRENCY_KEY, R.string.customize_currencies) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            val prefs = LocalContext.current.prefs()
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.customize_currencies_detail)) },
                initialText = prefs.getString(setting.key, Defaults.PREF_CUSTOM_CURRENCY_KEY)!!,
                onConfirmed = { prefs.edit { putString(setting.key, it) }; KeyboardLayoutSet.onSystemLocaleChanged() },
                title = { Text(stringResource(R.string.customize_currencies)) },
                neutralButtonText = if (prefs.contains(setting.key)) stringResource(R.string.button_default) else null,
                onNeutral = { prefs.edit { remove(setting.key)}; KeyboardLayoutSet.onSystemLocaleChanged() },
                checkTextValid = { text -> text.splitOnWhitespace().none { it.length > 8 } }
            )
        }
    },
    Setting(context, Settings.PREF_MORE_POPUP_KEYS, R.string.show_popup_keys_title) {
        val items = listOf(POPUP_KEYS_NORMAL, POPUP_KEYS_MAIN, POPUP_KEYS_MORE, POPUP_KEYS_ALL).map { setting ->
            stringResource(morePopupKeysResId(setting)) to setting
        }
        ListPreference(it, items, Defaults.PREF_MORE_POPUP_KEYS) { KeyboardLayoutSet.onSystemLocaleChanged() }
    },
    Setting(context, SettingsWithoutKey.BACKUP_RESTORE, R.string.backup_restore_title) {
        BackupRestorePreference(it)
    },
    Setting(context, Settings.PREF_TIMESTAMP_FORMAT, R.string.timestamp_format_title) { setting ->
        TextInputPreference(setting, Defaults.PREF_TIMESTAMP_FORMAT) { checkTimestampFormat(it) }
    },
    Setting(context, SettingsWithoutKey.DEBUG_SETTINGS, R.string.debug_settings_title) {
        Preference(
            name = it.title,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Debug) }
        ) { NextScreenIcon() }
    },
    Setting(context, Settings.PREF_EMOJI_MAX_SDK, R.string.prefs_key_emoji_max_sdk) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = 0,
            range = 21f..35f,
            description = {
                "Android " + when(it) {
                    21 -> "5.0"
                    22 -> "5.1"
                    23 -> "6"
                    24 -> "7.0"
                    25 -> "7.1"
                    26 -> "8.0"
                    27 -> "8.1"
                    28 -> "9"
                    29 -> "10"
                    30 -> "11"
                    31 -> "12"
                    32 -> "12L"
                    33 -> "13"
                    34 -> "14"
                    35 -> "15"
                    else -> "version unknown"
                }
            },
            onConfirmed = {
                SupportedEmojis.load(ctx)
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        )
    },
    Setting(context, Settings.PREF_URL_DETECTION, R.string.url_detection_title, R.string.url_detection_summary) {
        SwitchPreference(it, Defaults.PREF_URL_DETECTION)
    },

    Setting(context, SettingsWithoutKey.GEMINI_API_KEY, R.string.gemini_api_key_title, R.string.gemini_api_key_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        Preference(
            name = setting.title,
            description = if (service.hasApiKey()) "Key set" else stringResource(R.string.gemini_api_key_summary),
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.gemini_api_key_hint)) },
                initialText = service.getApiKey() ?: "",
                onConfirmed = { service.setApiKey(it) },
                title = { Text(stringResource(R.string.gemini_api_key_title)) },
                neutralButtonText = if (service.hasApiKey()) stringResource(R.string.delete) else null,
                onNeutral = { service.setApiKey(null) },
                extraContent = {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.material3.Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/app/apikey"))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }
                        ) {
                            Text(stringResource(R.string.get_api_key))
                        }
                    }
                }
            )
        }
    },
    Setting(context, SettingsWithoutKey.GEMINI_MODEL, R.string.gemini_model_title, R.string.gemini_model_summary) { setting ->
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        val items = helium314.keyboard.latin.utils.ProofreadService.AVAILABLE_MODELS.map { it to it }
        var selectedModel by remember { mutableStateOf(service.getModelName()) }
        ListPreference(
            setting = setting,
            items = items,
            default = selectedModel,
            onChanged = { newModel ->
                service.setModelName(newModel)
                selectedModel = newModel
            }
        )
    },
    Setting(context, SettingsWithoutKey.AI_PROVIDER, R.string.ai_provider_title, R.string.ai_provider_summary) { setting ->
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        val items = listOf(
            ctx.getString(R.string.ai_provider_huggingface) to helium314.keyboard.latin.utils.ProofreadService.AIProvider.GROQ.name,
            ctx.getString(R.string.ai_provider_gemini) to helium314.keyboard.latin.utils.ProofreadService.AIProvider.GEMINI.name,
            ctx.getString(R.string.ai_provider_openai) to helium314.keyboard.latin.utils.ProofreadService.AIProvider.OPENAI.name
        )
        var selectedProvider by remember { mutableStateOf(service.getProvider().name) }
        ListPreference(
            setting = setting,
            items = items,
            default = selectedProvider,
            onChanged = { newProvider ->
                service.setProvider(helium314.keyboard.latin.utils.ProofreadService.AIProvider.valueOf(newProvider))
                selectedProvider = newProvider
                // Trigger AI Integration screen recomposition
                helium314.keyboard.settings.screens.updateProviderState(newProvider)
            }
        )
    },
    Setting(context, SettingsWithoutKey.GROQ_TOKEN, R.string.groq_token_title, R.string.groq_token_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        var hasToken by remember { mutableStateOf(service.getGroqToken() != null) }
        
        Preference(
            name = setting.title,
            description = if (hasToken) "Key set" else "Not set",
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.groq_token_hint)) },
                initialText = service.getGroqToken() ?: "",
                onConfirmed = { 
                    service.setGroqToken(it)
                    hasToken = true
                },
                title = { Text(stringResource(R.string.groq_token_title)) },
                neutralButtonText = if (hasToken) stringResource(R.string.delete) else null,
                onNeutral = { 
                    service.setGroqToken(null)
                    hasToken = false
                },
                extraContent = {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    TextButton(
                        onClick = { uriHandler.openUri("https://console.groq.com/keys") }
                    ) {
                        Text(stringResource(R.string.get_groq_key))
                    }
                }
            )
        }
    },
    Setting(context, SettingsWithoutKey.HUGGINGFACE_TOKEN, R.string.huggingface_token_title, R.string.huggingface_token_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        Preference(
            name = setting.title,
            description = if (service.getHuggingFaceToken() != null) "Key set" else "Not set",
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.huggingface_token_hint)) },
                initialText = service.getHuggingFaceToken() ?: "",
                onConfirmed = { service.setHuggingFaceToken(it) },
                title = { Text(stringResource(R.string.huggingface_token_title)) },
                neutralButtonText = if (service.getHuggingFaceToken() != null) stringResource(R.string.delete) else null,
                onNeutral = { service.setHuggingFaceToken(null) },
                extraContent = {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    TextButton(
                        onClick = { uriHandler.openUri("https://huggingface.co/settings/tokens") }
                    ) {
                        Text(stringResource(R.string.get_huggingface_key))
                    }
                }
            )
        }
    },
    Setting(context, SettingsWithoutKey.HUGGINGFACE_MODEL, R.string.huggingface_model_title, R.string.huggingface_model_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        Preference(
            name = setting.title,
            description = service.getHuggingFaceModel(),
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.huggingface_model_hint)) },
                initialText = service.getHuggingFaceModel(),
                onConfirmed = { service.setHuggingFaceModel(it) },
                title = { Text(stringResource(R.string.huggingface_model_title)) }
            )
        }
    },
    Setting(context, SettingsWithoutKey.GROQ_MODEL, R.string.huggingface_model_title, R.string.huggingface_model_summary) { setting ->
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        val items = helium314.keyboard.latin.utils.GroqModels.AVAILABLE_MODELS.map { it to it }
        
        var selectedModel by remember { mutableStateOf(service.getGroqModel()) }
        
        ListPreference(
            setting = setting,
            items = items,
            default = selectedModel,
            onChanged = { newModel ->
                service.setGroqModel(newModel)
                selectedModel = newModel
            }
        )
    },
    Setting(context, SettingsWithoutKey.HUGGINGFACE_ENDPOINT, R.string.huggingface_endpoint_title, R.string.huggingface_endpoint_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        Preference(
            name = setting.title,
            description = service.getHuggingFaceEndpoint(),
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.huggingface_endpoint_hint)) },
                initialText = service.getHuggingFaceEndpoint(),
                onConfirmed = { service.setHuggingFaceEndpoint(it) },
                title = { Text(stringResource(R.string.huggingface_endpoint_title)) }
            )
        }
    },
    Setting(context, SettingsWithoutKey.GEMINI_TARGET_LANGUAGE, R.string.translate_target_language_title, R.string.translate_target_language_summary) { setting ->
        val ctx = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
        val languageNames = ctx.resources.getStringArray(helium314.keyboard.latin.R.array.translate_language_names)
        val languageCodes = ctx.resources.getStringArray(helium314.keyboard.latin.R.array.translate_language_codes)
        val items = languageNames.zip(languageCodes)
        var selectedLanguage by remember { mutableStateOf(service.getTargetLanguage()) }
        ListPreference(
            setting = setting,
            items = items,
            default = selectedLanguage,
            onChanged = { newLanguage ->
                service.setTargetLanguage(newLanguage)
                selectedLanguage = newLanguage
            }
        )
    },
    if (BuildConfig.FLAVOR == "standard") Setting(context, SettingsWithoutKey.CUSTOM_AI_KEYS, R.string.custom_ai_keys_title, R.string.custom_ai_keys_summary) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.CustomAIKeys) }
        ) { NextScreenIcon() }
    } else null,
    if (BuildConfig.FLAVOR == "offline") Setting(context, SettingsWithoutKey.OFFLINE_KEEP_MODEL_LOADED, R.string.offline_keep_model_loaded_title, R.string.offline_keep_model_loaded_summary) {
        SwitchPreference(it, Defaults.PREF_OFFLINE_KEEP_MODEL_LOADED)
    } else null,
    if (BuildConfig.FLAVOR == "offline") Setting(context, SettingsWithoutKey.OFFLINE_MODEL_PATH, R.string.offline_model_title, R.string.offline_model_summary) { setting ->
        val context = LocalContext.current
        val service = remember { helium314.keyboard.latin.utils.ProofreadService(context) }
        var encoderPath by remember { mutableStateOf(service.getModelPath()) }
        var decoderPath by remember { mutableStateOf(service.getDecoderPath()) }
        var tokenizerPath by remember { mutableStateOf(service.getTokenizerPath()) }
        
        // Encoder file picker
        val encoderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                try {
                     context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                     Log.e("AdvancedScreen", "Failed to take persistable permission", e)
                }
                service.setModelPath(it.toString())
                encoderPath = it.toString()
                FeedbackManager.message(context, "Encoder selected")
            }
        }
        
        // Decoder file picker
        val decoderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                try {
                     context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                     Log.e("AdvancedScreen", "Failed to take persistable permission", e)
                }
                service.setDecoderPath(it.toString())
                decoderPath = it.toString()
                FeedbackManager.message(context, "Decoder selected")
            }
        }
        
        // Tokenizer file picker
        val tokenizerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                try {
                     context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                     Log.e("AdvancedScreen", "Failed to take persistable permission", e)
                }
                service.setTokenizerPath(it.toString())
                tokenizerPath = it.toString()
                FeedbackManager.message(context, "Tokenizer selected")
            }
        }

        androidx.compose.foundation.layout.Column {
            // Encoder (required)
            Preference(
                name = "Encoder Model (.onnx)", 
                description = if (encoderPath != null) service.getModelName() else "Required - select encoder ONNX file",
                onClick = { encoderLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            )
            
            // Decoder (required for generation)
            Preference(
                name = "Decoder Model (.onnx)", 
                description = if (decoderPath != null) "Selected" else "Required - select decoder ONNX file",
                onClick = { decoderLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            )
            
            // Tokenizer (required for proper tokenization)
            Preference(
                name = "Tokenizer (tokenizer.json)", 
                description = if (tokenizerPath != null) "Selected" else "Required - select tokenizer.json",
                onClick = { tokenizerLauncher.launch(arrayOf("application/json", "*/*")) }
            )

            if (encoderPath != null || decoderPath != null || tokenizerPath != null) {
                Preference(
                    name = "Remove All Models",
                    description = "Unload models and free memory",
                    onClick = { 
                        service.unloadModel()
                        service.setModelPath(null)
                        service.setDecoderPath(null)
                        service.setTokenizerPath(null)
                        encoderPath = null
                        decoderPath = null
                        tokenizerPath = null
                    }
                )
            }

            var showSystemPromptDialog by remember { mutableStateOf(false) }
            if (showSystemPromptDialog) {
                TextInputDialog(
                    title = { Text("System Instruction") },
                    initialText = service.getSystemPrompt(),
                    checkTextValid = { true },
                    onConfirmed = { 
                        service.setSystemPrompt(it)
                        showSystemPromptDialog = false 
                    },
                    onDismissRequest = { showSystemPromptDialog = false }
                )
            }

            Preference(
                name = "System Instruction",
                description = service.getSystemPrompt().takeIf { it.isNotBlank() } ?: "Default",
                onClick = { showSystemPromptDialog = true }
            )



            // Target Language for Translation
            val languageSetting = Setting(context, Settings.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE, R.string.translate_target_language_title, R.string.translate_target_language_summary) { }
            val languages = listOf("French", "German", "Romanian", "Spanish", "Italian", "Dutch", "Portuguese", "Russian", "Chinese", "Japanese")
            val languageItems = languages.map { it to it }
            ListPreference(
                setting = languageSetting,
                items = languageItems,
                default = Defaults.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sampling Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
            )



            val tokenEntries = context.resources.getStringArray(R.array.offline_max_tokens_entries)
            val tokenValues = context.resources.getStringArray(R.array.offline_max_tokens_values).map { it.toInt() }
            val tokenItems = tokenEntries.zip(tokenValues)
            val maxTokenSetting = Setting(context, Settings.PREF_OFFLINE_MAX_TOKENS, R.string.offline_max_tokens_title, R.string.offline_max_tokens_summary) { }

            ListPreference(
                setting = maxTokenSetting,
                items = tokenItems,
                default = Defaults.PREF_OFFLINE_MAX_TOKENS
            )
        }
    } else null
) // Close listOfNotNull
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AdvancedSettingsScreen { }
        }
    }
}
