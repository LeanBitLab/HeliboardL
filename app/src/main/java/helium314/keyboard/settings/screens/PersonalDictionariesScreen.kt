// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes
import helium314.keyboard.latin.utils.SubtypeSettings.getSystemLocales
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.settings.NextScreenIcon
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.previewDark
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.TreeSet
import java.util.zip.ZipInputStream
import android.provider.UserDictionary
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry

@Composable
fun PersonalDictionariesScreen(
    onClickBack: () -> Unit,
) {
    // todo: consider adding "add word" button like old settings (requires additional navigation parameter, should not be hard)
    val ctx = LocalContext.current
    val locales: MutableList<Locale?> = getSortedDictionaryLocales().toMutableList()
    locales.add(0, null)
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.edit_personal_dictionary)) },
        filteredItems = { term ->
            locales.filter { locale ->
                locale.getLocaleDisplayNameForUserDictSettings(ctx).replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
            }
        },
        itemContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        SettingsDestination.navigateTo(SettingsDestination.PersonalDictionary + (it?.toLanguageTag() ?: ""))
                    }
                    .heightIn(min = 44.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it.getLocaleDisplayNameForUserDictSettings(ctx), style = MaterialTheme.typography.bodyLarge)
                NextScreenIcon()
            }
        }
    )
    
    var showImporting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showImporting = true
            scope.launch(Dispatchers.IO) {
                try {
                    val count = importGboardDictionary(ctx, uri)
                    withContext(Dispatchers.Main) {
                        KeyboardSwitcher.getInstance().showToast("Imported $count words", true)
                        showImporting = false
                    }
                } catch (e: Exception) {
                    Log.e("ImportDict", "Failed to import", e)
                    withContext(Dispatchers.Main) {
                        KeyboardSwitcher.getInstance().showToast("Import failed: ${e.message}", true)
                        showImporting = false
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/plain")) },
            text = { Text("Import Gboard Dictionary") },
            icon = { Icon(painterResource(R.drawable.ic_plus), "Import Gboard Dictionary") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
        if (showImporting) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

private suspend fun importGboardDictionary(context: android.content.Context, uri: android.net.Uri): Int {
    var addedCount = 0
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        // Check if it's a zip by reading magic bytes or extension (but here we just try zip first)
        // Gboard export is typically a zip containing dictionary.txt
        // But users might extract it
        try {
            // Try as ZIP
            val zipStream = ZipInputStream(inputStream)
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".txt") || entry.name == "dictionary.txt") {
                    // Prevent closing the parent stream
                    val reader = BufferedReader(InputStreamReader(zipStream))
                    addedCount += parseAndInsert(context, reader)
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        } catch (e: Exception) {
            // Might be a plain text file
            context.contentResolver.openInputStream(uri)?.use { plainStream ->
                val reader = BufferedReader(InputStreamReader(plainStream))
                addedCount += parseAndInsert(context, reader)
            }
        }
    }
    return addedCount
}

private fun parseAndInsert(context: android.content.Context, reader: BufferedReader): Int {
    var count = 0
    reader.forEachLine { line ->
        if (line.startsWith("#")) return@forEachLine
        val parts = line.split("\t")
        if (parts.size >= 1) {
            val word = parts[0]
            if (word.isNotBlank()) {
                val shortcut = if (parts.size >= 2) parts[1].ifBlank { null } else null
                val localeStr = if (parts.size >= 3) parts[2].ifBlank { null } else null
                
                val locale = if (localeStr != null) {
                    try { Locale.forLanguageTag(localeStr) } catch(_: Exception) { null }
                } else null
                
                // Frequency is not always present or standardized, use default
                // UserDictionary.Words.addWord(context, word, 250, shortcut, locale)
                // We need to use valid locale
                try {
                UserDictionary.Words.addWord(context, word, 250, shortcut, locale)
                count++
                } catch (e: Exception) {
                    Log.w("ImportDict", "Failed to add word $word", e)
                }
            }
        }
    }
    return count
}

fun getSortedDictionaryLocales(): TreeSet<Locale> {
    val sortedLocales = sortedSetOf<Locale>(compareBy { it.toLanguageTag().lowercase() })

    // Add the main language selected in the "Language and Layouts" setting except "No language"
    for (mainSubtype in getEnabledSubtypes(true)) {
        val mainLocale = mainSubtype.locale()
        if (mainLocale.toLanguageTag() != SubtypeLocaleUtils.NO_LANGUAGE) {
            sortedLocales.add(mainLocale)
        }
        // Secondary language is added only if main language is selected
        val enabled = getEnabledSubtypes(false)
        for (subtype in enabled) {
            if (subtype.locale() == mainLocale) sortedLocales.addAll(getSecondaryLocales(subtype.extraValue))
        }
    }

    sortedLocales.addAll(getSystemLocales())
    return sortedLocales
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            PersonalDictionariesScreen { }
        }
    }
}
