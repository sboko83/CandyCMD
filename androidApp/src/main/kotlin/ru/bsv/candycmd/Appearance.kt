package ru.bsv.candycmd

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class Appearance(val title: Int) {
    SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark);

    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read() = Appearance.entries.firstOrNull { it.name == preferences.getString("theme", null) }
        ?: Appearance.SYSTEM
    fun write(value: Appearance) { preferences.edit().putString("theme", value.name).apply() }
}

@Composable
internal fun AppearanceDialog(selected: Appearance, onSelect: (Appearance) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Appearance.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .selectable(selected == option, role = Role.RadioButton, onClick = { onSelect(option) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == option, onClick = null)
                        Text(stringResource(option.title), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
}
