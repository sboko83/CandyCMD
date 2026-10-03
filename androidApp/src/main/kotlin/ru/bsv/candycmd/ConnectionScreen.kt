package ru.bsv.candycmd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal data class ConnectionUiState(
    val address: String,
    val message: String,
    val busy: Boolean,
    val permissionGranted: Boolean,
    val networkCount: Int,
    val selectedNetwork: Int?,
    val candidates: List<String>,
)

internal data class ConnectionActions(
    val editAddress: (String) -> Unit,
    val connect: () -> Unit,
    val discover: () -> Unit,
    val cancel: () -> Unit,
    val requestPermission: () -> Unit,
    val permissionSettings: () -> Unit,
    val wifiSettings: () -> Unit,
    val selectNetwork: (Int?) -> Unit,
    val selectCandidate: (Int) -> Unit,
)

/** Setup is shown only without a saved dryer, or when the owner explicitly searches again from the dryer screen. */
@Composable
internal fun ConnectionScreen(
    state: ConnectionUiState,
    actions: ConnectionActions,
    manualInitially: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val enabled = !state.busy
    var manual by rememberSaveable { mutableStateOf(manualInitially) }
    var help by rememberSaveable { mutableStateOf(false) }
    if (onBack != null) BackHandler(enabled = enabled, onBack = onBack)
    ScreenFrame(header = {
        if (onBack != null) BackHeader(onBack = onBack, enabled = enabled)
        else Column(Modifier.padding(bottom = 20.dp)) {
            Text(stringResource(R.string.my_dryer), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.dryer_family), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        ScreenTitle(stringResource(R.string.connect_dryer))
        ListCard {
            SetupStep(1, stringResource(R.string.setup_step_wifi), first = true)
            SetupStep(2, stringResource(R.string.setup_step_knob))
        }
        Spacer(Modifier.height(16.dp))
        when {
            !state.permissionGranted -> {
                AppButton(onClick = actions.requestPermission, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.grant_permission))
                }
                AppTextButton(onClick = actions.permissionSettings, modifier = Modifier.alignedText()) {
                    Text(stringResource(R.string.app_settings))
                }
            }
            state.networkCount == 0 -> AppButton(onClick = actions.wifiSettings, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wifi_settings))
            }
            else -> {
                if (state.networkCount > 1) {
                    SectionLabel(stringResource(R.string.choose_wifi), Modifier.padding(top = 0.dp))
                    ListCard {
                        repeat(state.networkCount) { index ->
                            ListRow(stringResource(R.string.wifi_option, index + 1, ""), first = index == 0, enabled = enabled,
                                onClick = { actions.selectNetwork(index) }) {
                                RadioButton(selected = state.selectedNetwork == index, onClick = null, enabled = enabled)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                AppButton(onClick = actions.discover, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("discover")) {
                    AppGlyph(R.drawable.ic_search)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.find_dryer))
                }
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
            AppTextButton(onClick = actions.cancel, modifier = Modifier.alignedText().padding(top = 4.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (state.message.isNotBlank()) Text(state.message,
            Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.candidates.isNotEmpty()) {
            SectionLabel(stringResource(R.string.found_title))
            ListCard {
                state.candidates.forEachIndexed { index, address ->
                    ListRow(stringResource(R.string.found_row, address), first = index == 0, enabled = enabled,
                        onClick = { actions.selectCandidate(index) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!manual) {
            AppTextButton(onClick = { manual = true }, enabled = enabled, modifier = Modifier.alignedText().testTag("manual-address")) {
                Text(stringResource(R.string.manual_address))
            }
        } else {
            SectionLabel(stringResource(R.string.manual_address))
            OutlinedTextField(value = state.address, onValueChange = actions.editAddress, enabled = enabled,
                label = { Text(stringResource(R.string.device_ip)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) actions.connect() }), modifier = Modifier.fillMaxWidth())
            AppOutlinedButton(onClick = actions.connect, enabled = enabled && state.address.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.connect_address)) }
        }
        Spacer(Modifier.height(8.dp))
        Disclosure(stringResource(R.string.connection_trouble), help, { help = !help })
        if (help) {
            Text(stringResource(R.string.connection_first_setup), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextButton(onClick = actions.wifiSettings, enabled = enabled, modifier = Modifier.alignedText()) {
                Text(stringResource(R.string.wifi_settings))
            }
            if (state.networkCount > 1 || state.selectedNetwork != null) {
                AppTextButton(onClick = { actions.selectNetwork(null) }, enabled = enabled, modifier = Modifier.alignedText()) {
                    Text(stringResource(R.string.wifi_automatic))
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: Int, text: String, first: Boolean = false) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$number", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
