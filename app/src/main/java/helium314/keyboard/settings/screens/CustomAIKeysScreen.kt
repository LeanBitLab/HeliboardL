package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
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
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_ai_keys_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                (1..10).forEach { index ->
                    CustomAIKeySlot(index, context)
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    )
}

@Composable
private fun CustomAIKeySlot(index: Int, context: Context) {
    val prefs = context.prefs()
    val prefKey = "pref_custom_ai_prompt_$index"
    
    var currentPrompt by remember { mutableStateOf(prefs.getString(prefKey, "") ?: "") }
    val isSet = currentPrompt.isNotBlank()
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

    val cardModifier = Modifier.fillMaxWidth()
    val cardColors = if (isSet) {
        CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    } else {
        CardDefaults.outlinedCardColors()
    }

    val CardContent = @Composable {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Key $index",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isSet) currentPrompt else "Not configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (isSet) {
               Icon(
                   painter = painterResource(R.drawable.ic_setup_check),
                   contentDescription = null, // decorative
                   tint = MaterialTheme.colorScheme.primary
               )
            }
        }
    }

    if (isSet) {
        ElevatedCard(
            onClick = { showDialog = true },
            modifier = cardModifier,
            colors = cardColors
        ) { CardContent() }
    } else {
        OutlinedCard(
            onClick = { showDialog = true },
            modifier = cardModifier,
            colors = cardColors
        ) { CardContent() }
    }
    
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { newPrompt ->
                val trimmed = newPrompt.trim()
                prefs.edit { putString(prefKey, trimmed) }
                currentPrompt = trimmed
                updateToolbarKeyStatus(context, keyEnum, trimmed.isNotEmpty())
            },
            title = { Text("Configure Key $index") },
            textInputLabel = { Text("Prompt (e.g. \"rewrite in poetic style\")") },
            initialText = currentPrompt,
            neutralButtonText = if (isSet) "Clear" else null,
            onNeutral = {
                prefs.edit { remove(prefKey) }
                currentPrompt = ""
                updateToolbarKeyStatus(context, keyEnum, false)
            },
            icon = {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
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
            entries[existingIndex] = "${key.name},true"
        } else {
            entries.add("${key.name},true")
        }
    } else {
        if (existingIndex != -1) {
            entries[existingIndex] = "${key.name},false"
        }
    }
    
    prefs.edit { putString(Settings.PREF_TOOLBAR_KEYS, entries.joinToString(";")) }
}
