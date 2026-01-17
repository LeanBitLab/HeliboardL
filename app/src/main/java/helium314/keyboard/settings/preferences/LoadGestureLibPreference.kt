// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.latin.utils.GestureLibraryDownloader
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.content.edit

@SuppressLint("ApplySharedPref")
@Composable
fun LoadGestureLibPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var isDownloading by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val prefs = ctx.protectedPrefs()
    val abi = Build.SUPPORTED_ABIS[0]
    val libFile = File(ctx.filesDir?.absolutePath + File.separator + JniUtils.JNI_LIB_IMPORT_FILE_NAME)
    val scope = rememberCoroutineScope()
    
    fun renameToLibFileAndRestart(file: File, checksum: String) {
        libFile.setWritable(true)
        libFile.delete()
        // store checksum in default preferences (see JniUtils)
        prefs.edit(commit = true) { putString(Settings.PREF_LIBRARY_CHECKSUM, checksum) }
        file.copyTo(libFile)
        libFile.setReadOnly()
        file.delete()
        Runtime.getRuntime().exit(0) // exit will restart the app, so library will be loaded
    }
    
    fun startDownload() {
        isDownloading = true
        scope.launch {
            GestureLibraryDownloader.downloadLibrary(ctx).fold(
                onSuccess = { downloadedFile ->
                    val checksum = ChecksumCalculator.checksum(downloadedFile) ?: ""
                    Toast.makeText(ctx, R.string.load_gesture_library_download_success, Toast.LENGTH_SHORT).show()
                    renameToLibFileAndRestart(downloadedFile, checksum)
                },
                onFailure = { error ->
                    isDownloading = false
                    Toast.makeText(
                        ctx, 
                        ctx.getString(R.string.load_gesture_library_download_failed, error.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
    
    var tempFilePath: String? by rememberSaveable { mutableStateOf(null) }
    val launcher = filePicker { uri ->
        val tmpfile = File(ctx.filesDir.absolutePath + File.separator + "tmplib")
        try {
            val otherTemporaryFile = File(ctx.filesDir.absolutePath + File.separator + "tmpfile")
            FileUtils.copyContentUriToNewFile(uri, ctx, otherTemporaryFile)
            val inputStream = FileInputStream(otherTemporaryFile)
            val outputStream = FileOutputStream(tmpfile)
            outputStream.use {
                tmpfile.setReadOnly() // as per recommendations in https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading
                FileUtils.copyStreamToOtherStream(inputStream, it)
            }
            otherTemporaryFile.delete()

            val checksum = ChecksumCalculator.checksum(tmpfile) ?: ""
            if (checksum == JniUtils.expectedDefaultChecksum()) {
                renameToLibFileAndRestart(tmpfile, checksum)
            } else {
                tempFilePath = tmpfile.absolutePath
            }
        } catch (e: IOException) {
            tmpfile.delete()
            // should inform user, but probably the issues will only come when reading the library
        }
    }
    
    Preference(
        name = setting.title,
        onClick = { showDialog = true }
    )
    
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { if (!isDownloading) showDialog = false },
            onConfirmed = {
                if (!isDownloading) {
                    // Download is the primary action
                    if (helium314.keyboard.latin.BuildConfig.FLAVOR != "offline") {
                         startDownload()
                    }
                }
            },
            confirmButtonText = if (helium314.keyboard.latin.BuildConfig.FLAVOR == "offline") "" else if (isDownloading) 
                stringResource(R.string.load_gesture_library_downloading) 
            else 
                stringResource(R.string.load_gesture_library_button_download),
            title = { Text(stringResource(R.string.load_gesture_library)) },
            content = { 
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.load_gesture_library_message, abi))
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator()
                    }
                }
            },
            // Use neutral button for either Delete (if library exists) or Load from file (if not)
            neutralButtonText = when {
                BuildConfig.FLAVOR == "offline" && !libFile.exists() -> stringResource(R.string.load_gesture_library_button_load) // Only allow load if offline and not exists
                isDownloading -> null
                libFile.exists() -> stringResource(R.string.load_gesture_library_button_delete)
                else -> stringResource(R.string.load_gesture_library_button_load)
            },
            onNeutral = {
                if (libFile.exists()) {
                    // Delete the library
                    libFile.delete()
                    prefs.edit(commit = true) { remove(Settings.PREF_LIBRARY_CHECKSUM) }
                    Runtime.getRuntime().exit(0)
                } else {
                    // Load from file
                    showDialog = false
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream")
                    launcher.launch(intent)
                }
            }
        )
    }
    
    if (tempFilePath != null)
        ConfirmationDialog(
            onDismissRequest = {
                File(tempFilePath!!).delete()
                tempFilePath = null
            },
            content = { Text(stringResource(R.string.checksum_mismatch_message, abi)) },
            onConfirmed = {
                val tempFile = File(tempFilePath!!)
                renameToLibFileAndRestart(tempFile, ChecksumCalculator.checksum(tempFile) ?: "")
            }
        )
}
