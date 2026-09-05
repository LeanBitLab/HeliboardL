// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import helium314.keyboard.latin.ocr.OcrPluginLoader
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
fun LoadOcrPluginPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    restartOnSuccess: Boolean = true,
    onSuccess: (() -> Unit)? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var isDownloading by rememberSaveable { mutableStateOf(false) }
    var remoteVersion by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasInternet = remember {
        ctx.packageManager.checkPermission(
            "android.permission.INTERNET",
            ctx.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val hasPlugin = OcrPluginLoader.hasPlugin(ctx)
    val localVersion = remember(hasPlugin) { OcrPluginLoader.getPluginVersion(ctx) }
    val updateAvailable = remember(localVersion, remoteVersion) {
        if (localVersion != null && remoteVersion != null) {
            isUpdateAvailable(localVersion, remoteVersion!!)
        } else {
            false
        }
    }

    LaunchedEffect(hasPlugin) {
        if (!hasInternet) return@LaunchedEffect
        isCheckingUpdate = true
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/LeanBitLab/LeanType-OCR-Plugin/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "HeliboardL")
                conn.connect()
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val regex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val match = regex.find(response)
                    if (match != null) {
                        remoteVersion = match.groupValues[1]
                    }
                }
            } catch (_: Exception) {
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    val launcher = filePicker { uri ->
        val success = OcrPluginLoader.importPlugin(ctx, uri)
        showDialog = false
        if (success) {
            FeedbackManager.message(ctx, R.string.load_ocr_plugin_success)
            onSuccess?.invoke()
            if (restartOnSuccess) {
                scope.launch {
                    delay(2000)
                    Runtime.getRuntime().exit(0)
                }
            }
        } else {
            FeedbackManager.message(ctx, R.string.load_ocr_plugin_failed)
        }
    }

    fun startDownload() {
        if (!hasInternet) {
            showDialog = false
            val url = "https://github.com/LeanBitLab/LeanType-OCR-Plugin/releases"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                ctx.startActivity(intent)
                android.widget.Toast.makeText(ctx, "Opening GitHub releases in browser… download the APK and use 'Load APK from storage'", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Failed to open browser: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        isDownloading = true
        scope.launch(Dispatchers.IO) {
            val tempFile = File(ctx.cacheDir, "temp_ocr_plugin.apk")
            if (tempFile.exists()) tempFile.delete()

            val downloaded = OcrPluginLoader.downloadPluginApk(ctx, null, tempFile)

            withContext(Dispatchers.Main) {
                isDownloading = false
                showDialog = false
                if (downloaded) {
                    val success = OcrPluginLoader.importPluginFromTempFile(ctx, tempFile)
                    if (success) {
                        FeedbackManager.message(ctx, R.string.load_ocr_plugin_success)
                        onSuccess?.invoke()
                        if (restartOnSuccess) {
                            scope.launch {
                                delay(2000)
                                Runtime.getRuntime().exit(0)
                            }
                        }
                    } else {
                        FeedbackManager.message(ctx, R.string.load_ocr_plugin_failed)
                    }
                } else {
                    FeedbackManager.message(ctx, R.string.load_ocr_plugin_failed)
                }
            }
        }
    }

    val effectiveSummary = when {
        isDownloading -> "Downloading plugin..."
        hasPlugin -> if (localVersion != null) "Active v$localVersion" else "Active"
        else -> summary
    }

    Preference(
        name = title,
        description = effectiveSummary,
        icon = icon,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        PreferenceDialog(
            onDismissRequest = { if (!isDownloading) showDialog = false },
            title = stringResource(R.string.load_ocr_plugin),
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
                                val buttonText = when {
                                    updateAvailable -> "Update to $remoteVersion"
                                    remoteVersion != null -> "Download plugin ($remoteVersion)"
                                    else -> "Download plugin"
                                }
                                Text(buttonText)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showDialog = false
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("*/*")
                                try {
                                    launcher.launch(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Load APK from storage")
                        }

                        if (hasPlugin) {
                            Button(
                                onClick = {
                                    OcrPluginLoader.removePlugin(ctx)
                                    FeedbackManager.message(ctx, "OCR plugin removed. Restarting...")
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
                                Text(stringResource(R.string.load_ocr_plugin_button_delete))
                            }
                        }
                    }
                }
            }
        ) {
            val message = when {
                hasPlugin && updateAvailable -> "An update is available for the OCR plugin!\nLocal version: $localVersion\nLatest version: $remoteVersion\n\nDo you want to download and update?"
                hasPlugin -> "OCR plugin is active (version $localVersion).\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
                remoteVersion != null -> "Download the latest OCR plugin (version $remoteVersion) from GitHub, or load an APK from local storage.\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
                else -> "Download the OCR plugin from GitHub, or load an APK from local storage.\n\nWarning: loading external code can be a security risk. Only use a plugin from a source you trust."
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
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
