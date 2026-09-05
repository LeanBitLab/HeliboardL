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
import helium314.keyboard.latin.ai.OfflineAiLoader
import helium314.keyboard.settings.FeedbackManager
import helium314.keyboard.settings.dialogs.PreferenceDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun LoadOfflineAiPluginPreference(
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

    val hasPlugin = OfflineAiLoader.hasPlugin(ctx)
    val localVersion = remember(hasPlugin) { OfflineAiLoader.getPluginVersion(ctx) }

    LaunchedEffect(hasPlugin) {
        if (!hasInternet) return@LaunchedEffect
        isCheckingUpdate = true
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/LeanBitLab/LeanType-Offline-AI-Plugin/releases/latest")
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
                conn.disconnect()
            } catch (_: Exception) {
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    val launcher = filePicker { uri ->
        if (uri == null) return@filePicker
        val success = OfflineAiLoader.loadPlugin(ctx, uri)
        if (success) {
            FeedbackManager.message(ctx, "Offline AI plugin loaded. Restarting...")
            onSuccess?.invoke()
            showDialog = false
            if (restartOnSuccess) {
                scope.launch {
                    delay(2000)
                    Runtime.getRuntime().exit(0)
                }
            }
        } else {
            FeedbackManager.message(ctx, R.string.load_offline_ai_plugin_failed)
        }
    }

    fun startDownload() {
        if (!hasInternet) {
            showDialog = false
            val url = "https://github.com/LeanBitLab/LeanType-Offline-AI-Plugin/releases"
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
                val tempFile = File(ctx.cacheDir, "offline_ai_plugin_download_${System.currentTimeMillis()}.apk")
                val downloadSuccess = OfflineAiLoader.downloadPluginApk(ctx, remoteVersion, tempFile)
                if (!downloadSuccess) {
                    withContext<Unit>(Dispatchers.Main) {
                        isDownloading = false
                        FeedbackManager.message(ctx, R.string.load_offline_ai_plugin_failed)
                    }
                    return@launch
                }

                val success = OfflineAiLoader.loadPluginFromTempFile(ctx, tempFile)
                withContext<Unit>(Dispatchers.Main) {
                    isDownloading = false
                    if (success) {
                        FeedbackManager.message(ctx, "Offline AI plugin loaded. Restarting...")
                        onSuccess?.invoke()
                        showDialog = false
                        if (restartOnSuccess) {
                            scope.launch {
                                delay(2000)
                                Runtime.getRuntime().exit(0)
                            }
                        }
                    } else {
                        FeedbackManager.message(ctx, R.string.load_offline_ai_plugin_failed)
                    }
                }
            } catch (e: Exception) {
                withContext<Unit>(Dispatchers.Main) {
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
                                Text(if (updateAvailable) "Update" else if (hasInternet) "Download" else "Download in Browser")
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
                                    OfflineAiLoader.removePlugin(ctx)
                                    FeedbackManager.message(ctx, "Offline AI plugin removed. Restarting...")
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
                                Text(stringResource(R.string.load_offline_ai_plugin_button_delete))
                            }
                        }
                    }
                }
            }
        ) {
            val message = when {
                hasPlugin && updateAvailable -> "An update is available for the Offline AI plugin!\nLocal version: $localVersion\nLatest version: $remoteVersion\n\nDo you want to update?"
                hasPlugin -> "Offline AI plugin is active (version $localVersion).\n\nEnables on-device GGUF / llama.cpp inference for proofreading and rewriting."
                remoteVersion != null -> "Download the latest Offline AI plugin (version $remoteVersion) from GitHub, or load an APK from local storage."
                else -> "Download the Offline AI plugin from GitHub, or load an APK from local storage to enable local GGUF proofreading."
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
