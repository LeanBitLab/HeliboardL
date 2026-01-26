package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.dialogs.TextInputDialog

@Composable
fun CustomAIKeysScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.custom_ai_keys_title)) },
        filteredItems = { emptyList<Int>() },
        itemContent = { },
        content = {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.custom_ai_keys_summary),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                HorizontalDivider()
                
                (1..10).forEach { index ->
                    CustomAIKeySlot(index, context)
                    HorizontalDivider()
                }
            }
        }
    )
}

@Composable
private fun CustomAIKeySlot(index: Int, context: Context) {
    val prefs = context.prefs()
    val prefKey = "pref_custom_ai_prompt_$index"
    
    // User requested to remove all default prompts and examples.
    val defaultPrompt = ""

    var currentPrompt by remember { mutableStateOf(prefs.getString(prefKey, "") ?: "") }
    var showDialog by remember { mutableStateOf(false) }
    
    val keyEnum = when(index) {
        1 -> ToolbarKey.CUSTOM_AI_1
        2 -> ToolbarKey.CUSTOM_AI_2
        3 -> ToolbarKey.CUSTOM_AI_3
        4 -> ToolbarKey.CUSTOM_AI_4
        5 -> ToolbarKey.CUSTOM_AI_5
        6 -> ToolbarKey.CUSTOM_AI_6
        7 -> ToolbarKey.CUSTOM_AI_7
        8 -> ToolbarKey.CUSTOM_AI_8
        9 -> ToolbarKey.CUSTOM_AI_9
        else -> ToolbarKey.CUSTOM_AI_10
    }
    
    val iconRes = when(index) {
        1 -> R.drawable.ic_custom_ai_1
        2 -> R.drawable.ic_custom_ai_2
        3 -> R.drawable.ic_custom_ai_3
        4 -> R.drawable.ic_custom_ai_4
        5 -> R.drawable.ic_custom_ai_5
        6 -> R.drawable.ic_custom_ai_6
        7 -> R.drawable.ic_custom_ai_7
        8 -> R.drawable.ic_custom_ai_8
        9 -> R.drawable.ic_custom_ai_9
        else -> R.drawable.ic_custom_ai_10
    }

    val displayPrompt = if (currentPrompt.isNotBlank()) currentPrompt else "Not set"

    Preference(
        name = "Key $index",
        description = displayPrompt,
        icon = iconRes,
        onClick = { showDialog = true }
    )
    
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { newPrompt ->
                val trimmed = newPrompt.trim()
                // Save whatever the user entered
                prefs.edit { putString(prefKey, trimmed) }
                currentPrompt = trimmed
                updateToolbarKeyStatus(context, keyEnum, trimmed.isNotEmpty())
                android.util.Log.d("CustomAIKeysScreen", "Key $index updated to: $trimmed")
            },
            title = { Text("Custom Prompt for Key $index") },
            textInputLabel = { Text("Enter custom prompt (use #editor, #proofread, etc.)") },
            initialText = currentPrompt,
            neutralButtonText = "Clear",
            onNeutral = {
                prefs.edit { remove(prefKey) }
                currentPrompt = ""
                updateToolbarKeyStatus(context, keyEnum, false)
                android.util.Log.d("CustomAIKeysScreen", "Key $index cleared")
            }
        )
    }
}

private fun updateToolbarKeyStatus(context: Context, key: ToolbarKey, enable: Boolean) {
    val prefs = context.prefs()
    val toolbarKeys = prefs.getString(Settings.PREF_TOOLBAR_KEYS, helium314.keyboard.latin.utils.defaultToolbarPref) ?: ""
    
    var entries = toolbarKeys.split(";").toMutableList()
    val keyEntryPrefix = "${key.name},"
    val existingIndex = entries.indexOfFirst { it.startsWith(keyEntryPrefix) }
    
    if (enable) {
        if (existingIndex != -1) {
            // Already exists, just ensure it is true
            entries[existingIndex] = "${key.name},true"
        } else {
            // Append to end
            entries.add("${key.name},true")
        }
    } else {
        if (existingIndex != -1) {
            // Set to false
            entries[existingIndex] = "${key.name},false"
        }
        // If not present, no need to do anything
    }
    
    prefs.edit { putString(Settings.PREF_TOOLBAR_KEYS, entries.joinToString(";")) }
}
