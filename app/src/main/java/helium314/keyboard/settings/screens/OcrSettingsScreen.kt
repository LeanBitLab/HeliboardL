// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ocr.OcrPluginLoader
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.LoadOcrPluginPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun OcrSettingsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    var ocrInstalled by remember { mutableStateOf(OcrPluginLoader.hasPlugin(context)) }

    var isCameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isCameraPermissionGranted = granted
    }

    val scriptOptions = remember {
        listOf(
            context.getString(R.string.ocr_script_all) to "all",
            context.getString(R.string.ocr_script_devanagari) to "devanagari",
            context.getString(R.string.ocr_script_latin) to "latin",
            context.getString(R.string.ocr_script_chinese) to "chinese",
            context.getString(R.string.ocr_script_japanese) to "japanese",
            context.getString(R.string.ocr_script_korean) to "korean"
        )
    }

    val casingOptions = remember {
        listOf(
            context.getString(R.string.ocr_casing_as_is) to "as_is",
            context.getString(R.string.ocr_casing_sentence) to "sentence",
            context.getString(R.string.ocr_casing_lower) to "lower",
            context.getString(R.string.ocr_casing_upper) to "upper",
            context.getString(R.string.ocr_casing_title_case) to "title"
        )
    }

    val lineJoinOptions = remember {
        listOf(
            context.getString(R.string.ocr_line_join_newline) to "newline",
            context.getString(R.string.ocr_line_join_space) to "space",
            context.getString(R.string.ocr_line_join_comma) to "comma",
            context.getString(R.string.ocr_line_join_bullet) to "bullet",
            context.getString(R.string.ocr_line_join_numbered) to "numbered"
        )
    }

    val settings = remember {
        listOf(
            OcrPluginLoader.PREF_OCR_SCRIPT,
            OcrPluginLoader.PREF_OCR_CASING,
            OcrPluginLoader.PREF_OCR_LINE_JOIN_FORMAT,
            OcrPluginLoader.PREF_OCR_KEEP_LINE_BREAKS,
            OcrPluginLoader.PREF_OCR_TRIM_WHITESPACE,
            OcrPluginLoader.PREF_OCR_DEHYPHENATE,
            OcrPluginLoader.PREF_OCR_NORMALIZE_PUNCTUATION,
            OcrPluginLoader.PREF_OCR_STRIP_BULLETS,
            OcrPluginLoader.PREF_OCR_REMOVE_NOISE,
            OcrPluginLoader.PREF_OCR_AUTO_COPY,
            OcrPluginLoader.PREF_OCR_AUTO_INSERT,
            OcrPluginLoader.PREF_OCR_SUGGEST_SCREENSHOT_TEXT,
            OcrPluginLoader.PREF_OCR_PERSIST_FLASH,
        )
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ocr_settings_title),
        settings = settings
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(vertical = 8.dp)
            ) {
                // Plugin Management Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        PreferenceCategory(stringResource(R.string.ocr_plugin_category))

                        LoadOcrPluginPreference(
                            title = "OCR Plugin APK",
                            summary = if (ocrInstalled) stringResource(R.string.libraries_status_active) else stringResource(R.string.libraries_status_not_installed),
                            icon = R.drawable.ic_ocr,
                            onSuccess = { ocrInstalled = OcrPluginLoader.hasPlugin(context) }
                        )
                    }
                }

                // Permissions Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        PreferenceCategory(stringResource(R.string.ocr_permissions_category))

                        Preference(
                            name = stringResource(R.string.ocr_camera_permission),
                            description = if (isCameraPermissionGranted) stringResource(R.string.ocr_camera_permission_granted) else stringResource(R.string.ocr_camera_permission_desc),
                            onClick = {
                                if (!isCameraPermissionGranted) {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            icon = R.drawable.ic_ocr
                        )
                    }
                }

                // Text Extraction & Formatting Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        PreferenceCategory(stringResource(R.string.ocr_text_options_category))

                        ListPreference(
                            setting = Setting(context, OcrPluginLoader.PREF_OCR_SCRIPT, R.string.ocr_script_title) {},
                            items = scriptOptions,
                            default = "all"
                        )

                        ListPreference(
                            setting = Setting(context, OcrPluginLoader.PREF_OCR_CASING, R.string.ocr_casing_title) {},
                            items = casingOptions,
                            default = "as_is"
                        )

                        ListPreference(
                            setting = Setting(context, OcrPluginLoader.PREF_OCR_LINE_JOIN_FORMAT, R.string.ocr_line_join_title) {},
                            items = lineJoinOptions,
                            default = "newline"
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_keep_line_breaks),
                            description = stringResource(R.string.ocr_keep_line_breaks_summary),
                            key = OcrPluginLoader.PREF_OCR_KEEP_LINE_BREAKS,
                            default = true
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_trim_whitespace),
                            description = stringResource(R.string.ocr_trim_whitespace_summary),
                            key = OcrPluginLoader.PREF_OCR_TRIM_WHITESPACE,
                            default = true
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_dehyphenate),
                            description = stringResource(R.string.ocr_dehyphenate_summary),
                            key = OcrPluginLoader.PREF_OCR_DEHYPHENATE,
                            default = true
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_normalize_punctuation),
                            description = stringResource(R.string.ocr_normalize_punctuation_summary),
                            key = OcrPluginLoader.PREF_OCR_NORMALIZE_PUNCTUATION,
                            default = false
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_strip_bullets),
                            description = stringResource(R.string.ocr_strip_bullets_summary),
                            key = OcrPluginLoader.PREF_OCR_STRIP_BULLETS,
                            default = false
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_remove_noise),
                            description = stringResource(R.string.ocr_remove_noise_summary),
                            key = OcrPluginLoader.PREF_OCR_REMOVE_NOISE,
                            default = true
                        )
                    }
                }

                // Workflow & Automation Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        PreferenceCategory(stringResource(R.string.ocr_workflow_category))

                        SwitchPreference(
                            name = stringResource(R.string.ocr_auto_copy),
                            description = stringResource(R.string.ocr_auto_copy_summary),
                            key = OcrPluginLoader.PREF_OCR_AUTO_COPY,
                            default = false
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_auto_insert),
                            description = stringResource(R.string.ocr_auto_insert_summary),
                            key = OcrPluginLoader.PREF_OCR_AUTO_INSERT,
                            default = false
                        )

                        SwitchPreference(
                            name = stringResource(R.string.ocr_suggest_screenshot_text),
                            description = stringResource(R.string.ocr_suggest_screenshot_text_summary),
                            key = OcrPluginLoader.PREF_OCR_SUGGEST_SCREENSHOT_TEXT,
                            default = true
                        )
                    }
                }

                // Camera & Viewfinder Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        PreferenceCategory(stringResource(R.string.ocr_camera_category))

                        SwitchPreference(
                            name = stringResource(R.string.ocr_persist_flash),
                            description = stringResource(R.string.ocr_persist_flash_summary),
                            key = OcrPluginLoader.PREF_OCR_PERSIST_FLASH,
                            default = false
                        )
                    }
                }
            }
        }
    }
}

fun createOcrSettings(context: Context): List<Setting> {
    val scriptOptions = listOf(
        context.getString(R.string.ocr_script_all) to "all",
        context.getString(R.string.ocr_script_devanagari) to "devanagari",
        context.getString(R.string.ocr_script_latin) to "latin",
        context.getString(R.string.ocr_script_chinese) to "chinese",
        context.getString(R.string.ocr_script_japanese) to "japanese",
        context.getString(R.string.ocr_script_korean) to "korean"
    )
    val casingOptions = listOf(
        context.getString(R.string.ocr_casing_as_is) to "as_is",
        context.getString(R.string.ocr_casing_sentence) to "sentence",
        context.getString(R.string.ocr_casing_lower) to "lower",
        context.getString(R.string.ocr_casing_upper) to "upper",
        context.getString(R.string.ocr_casing_title_case) to "title"
    )
    val lineJoinOptions = listOf(
        context.getString(R.string.ocr_line_join_newline) to "newline",
        context.getString(R.string.ocr_line_join_space) to "space",
        context.getString(R.string.ocr_line_join_comma) to "comma",
        context.getString(R.string.ocr_line_join_bullet) to "bullet",
        context.getString(R.string.ocr_line_join_numbered) to "numbered"
    )

    return listOf(
        Setting(context, OcrPluginLoader.PREF_OCR_SCRIPT, R.string.ocr_script_title) {
            ListPreference(it, scriptOptions, "all")
        },
        Setting(context, OcrPluginLoader.PREF_OCR_CASING, R.string.ocr_casing_title) {
            ListPreference(it, casingOptions, "as_is")
        },
        Setting(context, OcrPluginLoader.PREF_OCR_LINE_JOIN_FORMAT, R.string.ocr_line_join_title) {
            ListPreference(it, lineJoinOptions, "newline")
        },
        Setting(context, OcrPluginLoader.PREF_OCR_KEEP_LINE_BREAKS, R.string.ocr_keep_line_breaks, R.string.ocr_keep_line_breaks_summary) {
            SwitchPreference(it, true)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_TRIM_WHITESPACE, R.string.ocr_trim_whitespace, R.string.ocr_trim_whitespace_summary) {
            SwitchPreference(it, true)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_DEHYPHENATE, R.string.ocr_dehyphenate, R.string.ocr_dehyphenate_summary) {
            SwitchPreference(it, true)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_NORMALIZE_PUNCTUATION, R.string.ocr_normalize_punctuation, R.string.ocr_normalize_punctuation_summary) {
            SwitchPreference(it, false)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_STRIP_BULLETS, R.string.ocr_strip_bullets, R.string.ocr_strip_bullets_summary) {
            SwitchPreference(it, false)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_REMOVE_NOISE, R.string.ocr_remove_noise, R.string.ocr_remove_noise_summary) {
            SwitchPreference(it, true)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_AUTO_COPY, R.string.ocr_auto_copy, R.string.ocr_auto_copy_summary) {
            SwitchPreference(it, false)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_AUTO_INSERT, R.string.ocr_auto_insert, R.string.ocr_auto_insert_summary) {
            SwitchPreference(it, false)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_SUGGEST_SCREENSHOT_TEXT, R.string.ocr_suggest_screenshot_text, R.string.ocr_suggest_screenshot_text_summary) {
            SwitchPreference(it, true)
        },
        Setting(context, OcrPluginLoader.PREF_OCR_PERSIST_FLASH, R.string.ocr_persist_flash, R.string.ocr_persist_flash_summary) {
            SwitchPreference(it, false)
        },
    )
}
