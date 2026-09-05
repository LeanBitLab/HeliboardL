// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.translation.TranslationLoader
import helium314.keyboard.settings.FeedbackManager
import helium314.keyboard.settings.dialogs.PreferenceDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun LoadTranslationPluginPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    restartOnSuccess: Boolean = true,
    onSuccess: (() -> Unit)? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var isDownloading by rememberSaveable { mutableStateOf(false) }
    var remoteVersion by remember { mutableStateOf<String?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasInternet = remember {
        ctx.packageManager.checkPermission(
            "android.permission.INTERNET",
            ctx.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val hasPlugin = TranslationLoader.hasPlugin(ctx)
    val localVersion = remember(hasPlugin) { TranslationLoader.getPluginVersion(ctx) }

    LaunchedEffect(hasPlugin) {
        if (!hasInternet) return@LaunchedEffect
        isCheckingUpdate = true
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/LeanBitLab/LeanType-Translation-Plugin/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HeliboardL")
                conn.connect()
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val regex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val match = regex.find(response)
                    if (match != null) {
                        val tag = match.groupValues[1]
                        remoteVersion = tag
                        if (hasPlugin && localVersion != null) {
                            updateAvailable = isUpdateAvailable(localVersion, tag)
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore network errors
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    val launcher = filePicker { uri ->
        val success = TranslationLoader.importPlugin(ctx, uri)
        showDialog = false
        if (success) {
            FeedbackManager.message(ctx, "Translation plugin loaded. Restarting...")
            onSuccess?.invoke()
            if (restartOnSuccess) {
                scope.launch {
                    delay(2000)
                    Runtime.getRuntime().exit(0)
                }
            }
        } else {
            FeedbackManager.message(ctx, R.string.load_translation_plugin_failed)
        }
    }

    fun startDownload() {
        if (!hasInternet) {
            showDialog = false
            val url = "https://github.com/LeanBitLab/LeanType-Translation-Plugin/releases"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                ctx.startActivity(intent)
                Toast.makeText(ctx, "Opening GitHub releases in browser… download the APK and use 'Load from file'", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Failed to open browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        isDownloading = true
        scope.launch(Dispatchers.IO) {
            try {
                val tag = remoteVersion ?: "latest"
                val tempFile = File(ctx.cacheDir, "temp_translation_plugin.apk")
                val downloaded = TranslationLoader.downloadPluginApk(ctx, tag, tempFile)
                if (!downloaded) {
                    throw IOException("Failed to download translation plugin APK")
                }

                val success = TranslationLoader.importPlugin(ctx, Uri.fromFile(tempFile))
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    isDownloading = false
                    if (success) {
                        FeedbackManager.message(ctx, "Translation plugin loaded. Restarting...")
                        onSuccess?.invoke()
                        showDialog = false
                        if (restartOnSuccess) {
                            scope.launch {
                                delay(2000)
                                Runtime.getRuntime().exit(0)
                            }
                        }
                    } else {
                        FeedbackManager.message(ctx, R.string.load_translation_plugin_failed)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    Toast.makeText(ctx, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Preference(
        name = title,
        description = summary,
        icon = icon,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        PreferenceDialog(
            onDismissRequest = { if (!isDownloading) showDialog = false },
            title = title,
            showCloseButton = !isDownloading,
            buttons = {
                if (isDownloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Downloading...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasPlugin || updateAvailable) {
                            Button(
                                onClick = { startDownload() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (updateAvailable) "Update" else "Download")
                            }
                        }
                        if (!hasPlugin) {
                            OutlinedButton(
                                onClick = {
                                    showDialog = false
                                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("*/*")
                                    try {
                                        launcher.launch(intent)
                                    } catch (_: Exception) { }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Load from file")
                            }
                        }
                        if (hasPlugin) {
                            Button(
                                onClick = {
                                    TranslationLoader.removePlugin(ctx)
                                    FeedbackManager.message(ctx, "Translation plugin removed. Restarting...")
                                    onSuccess?.invoke()
                                    showDialog = false
                                    if (restartOnSuccess) {
                                        scope.launch {
                                            delay(2000)
                                            Runtime.getRuntime().exit(0)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.load_translation_plugin_button_delete))
                            }
                        }
                    }
                }
            }
        ) {
            val message = when {
                hasPlugin && updateAvailable -> "An update is available for the translation plugin!\nLocal version: $localVersion\nLatest version: $remoteVersion\n\nDo you want to download and update?"
                hasPlugin -> "Translation plugin is active (version $localVersion).\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
                remoteVersion != null -> "Download the latest translation plugin (version $remoteVersion) from GitHub, or load an APK from local storage.\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
                else -> "Download the translation plugin from GitHub, or load an APK from local storage.\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
            }
            Text(message)
        }
    }
}

private fun isUpdateAvailable(local: String, remote: String): Boolean {
    val cleanLocal = local.removePrefix("v").trim()
    val cleanRemote = remote.removePrefix("v").trim()
    if (cleanLocal == cleanRemote) return false

    val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }
    val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }

    val maxLength = maxOf(localParts.size, remoteParts.size)
    for (i in 0 until maxLength) {
        val localPart = localParts.getOrElse(i) { 0 }
        val remotePart = remoteParts.getOrElse(i) { 0 }
        if (remotePart > localPart) return true
        if (localPart > remotePart) return false
    }
    return false
}

@Composable
fun TranslationModePreference() {
    val ctx = LocalContext.current
    val items = listOf(
        ctx.getString(R.string.pref_translation_mode_auto) to "auto",
        ctx.getString(R.string.pref_translation_mode_offline_only) to "offline_only",
        ctx.getString(R.string.pref_translation_mode_online_only) to "online_only"
    )
    val setting = remember {
        helium314.keyboard.settings.Setting(
            key = "pref_translation_mode",
            title = ctx.getString(R.string.pref_translation_mode_title)
        ) {
            ListPreference(
                setting = it,
                items = items,
                default = "auto",
                icon = R.drawable.ic_translate
            )
        }
    }
    setting.Preference()
}

@Composable
fun TranslationEnginePreference() {
    val ctx = LocalContext.current
    val isOfflineFlavor = helium314.keyboard.latin.BuildConfig.FLAVOR == "offline"
    val items = if (isOfflineFlavor) {
        listOf(
            "Translation Plugin (ML Kit)" to "plugin",
            "Built-in AI (Local GGUF)" to "ai"
        )
    } else {
        listOf(
            "Translation Plugin (ML Kit)" to "plugin",
            "Built-in AI (Gemini/Groq/OpenAI)" to "ai"
        )
    }
    val setting = remember {
        helium314.keyboard.settings.Setting(
            ctx,
            helium314.keyboard.settings.SettingsWithoutKey.TRANSLATION_ENGINE,
            R.string.translation_engine_title,
            R.string.translation_engine_summary
        ) { setting ->
            ListPreference(
                setting = setting,
                items = items,
                default = "plugin",
                icon = R.drawable.ic_translate
            )
        }
    }
    setting.Preference()
}

@Composable
fun TranslationTargetLanguagePreference() {
    val ctx = LocalContext.current
    val setting = remember {
        helium314.keyboard.settings.Setting(
            ctx,
            helium314.keyboard.settings.SettingsWithoutKey.GEMINI_TARGET_LANGUAGE,
            R.string.translate_target_language_title,
            R.string.translate_target_language_summary
        ) { setting ->
            val service = remember { helium314.keyboard.latin.utils.ProofreadService(ctx) }
            val languageNames = ctx.resources.getStringArray(R.array.translate_language_names)
            val languageCodes = ctx.resources.getStringArray(R.array.translate_language_codes)
            var selectedLanguage by remember { mutableStateOf(service.getTargetLanguage()) }
            var showPickerDialog by remember { mutableStateOf(false) }
            var showCustomDialog by remember { mutableStateOf(false) }
            var listVersion by remember { mutableStateOf(0) }

            val items = remember(selectedLanguage, listVersion) {
                val zipped = languageNames.zip(languageCodes).toMutableList()
                val history = helium314.keyboard.latin.utils.TranslationUtils.getLanguageHistory(ctx.prefs())
                val removed = helium314.keyboard.latin.utils.TranslationUtils.getRemovedLanguages(ctx.prefs())
                val filteredZipped = zipped.filter { it.first.lowercase() !in removed && it.second.lowercase() !in removed }.toMutableList()
                for (h in history.reversed()) {
                    if (h.first.lowercase() !in removed && h.second.lowercase() !in removed && filteredZipped.none { helium314.keyboard.latin.utils.TranslationUtils.isSameLanguage(it, h) }) {
                        filteredZipped.add(0, h.first to h.second)
                    }
                }
                if (selectedLanguage.isNotEmpty() && filteredZipped.none { it.second.equals(selectedLanguage, ignoreCase = true) }) {
                    filteredZipped.add(0, selectedLanguage to selectedLanguage)
                }
                filteredZipped
            }

            val displayLabel = remember(selectedLanguage, items) {
                val found = items.find { it.second.equals(selectedLanguage, ignoreCase = true) }
                if (found != null) {
                    "${found.first} (${found.second})"
                } else {
                    selectedLanguage
                }
            }

            Preference(
                name = stringResource(R.string.translate_target_language_title),
                description = displayLabel,
                icon = R.drawable.ic_settings_languages,
                onClick = { showPickerDialog = true }
            )

            if (showPickerDialog) {
                helium314.keyboard.settings.dialogs.ConfirmationDialog(
                    onDismissRequest = { showPickerDialog = false },
                    onConfirmed = { showPickerDialog = false },
                    confirmButtonText = null,
                    cancelButtonText = null,
                    neutralButtonText = "+ Custom Language",
                    onNeutral = {
                        showPickerDialog = false
                        showCustomDialog = true
                    },
                    title = { Text(stringResource(R.string.translate_target_language_title)) },
                    content = {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                        ) {
                            items(items.size) { i ->
                                val (name, code) = items[i]
                                val isSelected = code.equals(selectedLanguage, ignoreCase = true)
                                val isDefault = languageCodes.contains(code)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            service.setTargetLanguage(code)
                                            ctx.prefs().edit().apply {
                                                putString(helium314.keyboard.settings.SettingsWithoutKey.GEMINI_TARGET_LANGUAGE, code)
                                                putString(helium314.keyboard.latin.settings.Settings.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE, name)
                                            }.apply()
                                            selectedLanguage = code
                                            helium314.keyboard.latin.utils.TranslationUtils.saveLanguageHistory(ctx.prefs(), name, code)
                                            showPickerDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (isSelected) "✓ $name ($code)" else "$name ($code)",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (!isDefault) {
                                        IconButton(
                                            onClick = {
                                                helium314.keyboard.latin.utils.TranslationUtils.removeLanguageHistory(ctx.prefs(), code)
                                                listVersion++
                                            }
                                        ) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close),
                                                contentDescription = "Delete language"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }

            if (showCustomDialog) {
                var customLangName by remember { mutableStateOf("") }
                var customLangCode by remember { mutableStateOf("") }
                helium314.keyboard.settings.dialogs.ConfirmationDialog(
                    onDismissRequest = { showCustomDialog = false },
                    onConfirmed = {
                        if (customLangName.isNotBlank() && customLangCode.isNotBlank()) {
                            val cleanName = customLangName.trim()
                            val cleanCode = customLangCode.trim()
                            helium314.keyboard.latin.utils.TranslationUtils.saveLanguageHistory(ctx.prefs(), cleanName, cleanCode)
                            ctx.prefs().edit().apply {
                                putString(helium314.keyboard.settings.SettingsWithoutKey.GEMINI_TARGET_LANGUAGE, cleanCode)
                                putString(helium314.keyboard.latin.settings.Settings.PREF_OFFLINE_TRANSLATE_TARGET_LANGUAGE, cleanName)
                            }.apply()
                            service.setTargetLanguage(cleanCode)
                            selectedLanguage = cleanCode
                            listVersion++
                        }
                        showCustomDialog = false
                    },
                    title = { Text("Add Custom Language") },
                    content = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(
                                value = customLangName,
                                onValueChange = { customLangName = it },
                                label = { Text("Language Name (e.g. Sanskrit)") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = customLangCode,
                                onValueChange = { customLangCode = it },
                                label = { Text("Language Code (e.g. sa)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                )
            }
        }
    }
    setting.Preference()
}
