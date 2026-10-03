package ru.bsv.candycmd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.bsv.candycmd.shared.monitor.MonitorState

internal data class DryerActions(
    val back: () -> Unit,
    val findAgain: () -> Unit,
    val manualAddress: () -> Unit,
    val wifiSettings: () -> Unit,
    val appearance: () -> Unit,
    val forget: () -> Unit,
)

@Composable
internal fun DryerScreen(
    address: String,
    link: LinkState,
    monitor: MonitorState,
    appearance: Appearance,
    busy: Boolean,
    actions: DryerActions,
) {
    BackHandler(onBack = actions.back)
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    ScreenFrame(header = { BackHeader(onBack = actions.back) }) {
        ScreenTitle(stringResource(R.string.dryer_title))
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dryer_family), style = MaterialTheme.typography.titleMedium)
                    Text(address.ifBlank { stringResource(R.string.address_unknown) }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(link)
            }
        }
        SectionLabel(stringResource(R.string.section_connection))
        ListCard {
            ListRow(stringResource(R.string.find_again), first = true, subtitle = stringResource(R.string.find_again_help),
                enabled = !busy, onClick = actions.findAgain)
            ListRow(stringResource(R.string.manual_address), enabled = !busy, onClick = actions.manualAddress)
            ListRow(stringResource(R.string.phone_wifi), onClick = actions.wifiSettings)
        }
        SectionLabel(stringResource(R.string.section_app))
        ListCard {
            ListRow(stringResource(R.string.appearance), first = true, value = stringResource(appearance.title), onClick = actions.appearance)
            ListRow(stringResource(R.string.diagnostics), subtitle = stringResource(R.string.diagnostics_help),
                onClick = { diagnostics = !diagnostics }) {
                AppGlyph(if (diagnostics) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (diagnostics) DiagnosticsPanel(monitor)
        Spacer(Modifier.height(20.dp))
        AppTextButton(onClick = { forget = true }, enabled = !busy, modifier = Modifier.alignedText().testTag("forget-dryer"),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.forget_dryer))
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
            if (BuildConfig.DEBUG) " · debug" else "", Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false },
        title = { Text(stringResource(R.string.forget_confirm)) }, text = { Text(stringResource(R.string.forget_help)) },
        confirmButton = {
            AppTextButton(onClick = { forget = false; actions.forget() }, enabled = !busy, modifier = Modifier.testTag("confirm-forget"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.forget_dryer))
            }
        },
        dismissButton = { AppTextButton(onClick = { forget = false }) { Text(stringResource(R.string.cancel)) } })
}
