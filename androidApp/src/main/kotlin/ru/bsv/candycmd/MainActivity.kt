package ru.bsv.candycmd

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import ru.bsv.candycmd.shared.connection.*

class MainActivity : ComponentActivity() {
    private lateinit var model: DryerViewModel
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { model.refreshNetworks() } }
        override fun onLost(lost: Network) {
            runOnUiThread {
                model.networkLost(lost)
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.restore() else model.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[DryerViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            val appearanceStore = remember { AppearanceStore(applicationContext) }
            var appearance by remember { mutableStateOf(appearanceStore.read()) }
            val dark = appearance.isDark(isSystemInDarkTheme())
            SideEffect {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CandyTheme(darkTheme = dark) {
                AppScreen(appearance) { appearance = it; appearanceStore.write(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        model.start()
    }

    override fun onStop() {
        model.stop()
        connectivity.unregisterNetworkCallback(callback)
        super.onStop()
    }

    private enum class Route { HOME, WIZARD, DRYER }

    @Composable
    private fun AppScreen(appearance: Appearance, onAppearance: (Appearance) -> Unit) {
        val state by model.monitor.state.collectAsState()
        val commandState by model.controller.state.collectAsState()
        val networks = model.networks
        val candidates = model.candidates
        var appearanceOpen by remember { mutableStateOf(false) }
        var route by rememberSaveable { mutableStateOf(Route.HOME) }
        var draft by rememberSaveable(stateSaver = StartDraft.Saver) { mutableStateOf(StartDraft()) }
        var manualSetup by rememberSaveable { mutableStateOf(false) }
        val link = state.link(connecting = model.restoring || model.busy && state.status == null)
        val openWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        val requestPermission = { permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }

        if (!model.showMonitor && !model.restoring) {
            ConnectionScreen(
                ConnectionUiState(model.ip, model.message, model.busy, model.permissionGranted,
                    networks.size, model.selected?.let { networks.indexOf(it) },
                    candidates.map { it.address.value }),
                ConnectionActions(
                    editAddress = { model.ip = it }, connect = { model.connect() },
                    discover = { model.discover() }, cancel = model::cancelOperation,
                    requestPermission = requestPermission,
                    permissionSettings = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    wifiSettings = openWifi,
                    selectNetwork = { model.selectNetwork(it?.let(networks::get)) },
                    selectCandidate = { model.connect(candidates[it]) },
                ),
                manualInitially = manualSetup,
                onBack = if (model.hasProfile) ({ model.openMonitor() }) else null,
            )
        } else when (route) {
            Route.WIZARD -> StartWizard(draft, { draft = it }, state, commandState, model.busy,
                canSaveFavorite = model.presetsReady && !model.savingPreset && model.presets.size < 50,
                favoritePrograms = model.favoritePrograms, onFavoriteProgram = model::toggleFavoriteProgram,
                onClose = { route = Route.HOME },
                onStart = { settings, favorite ->
                    model.start(settings, favorite)
                    draft = StartDraft()
                    route = Route.HOME
                })
            Route.DRYER -> DryerScreen(model.ip, link, state, appearance, model.busy, DryerActions(
                back = { route = Route.HOME },
                findAgain = { manualSetup = false; route = Route.HOME; model.openConnection() },
                manualAddress = { manualSetup = true; route = Route.HOME; model.openConnection() },
                wifiSettings = openWifi,
                appearance = { appearanceOpen = true },
                forget = { route = Route.HOME; model.forget() },
            ))
            Route.HOME -> ScreenFrame(header = { HomeHeader(link) { route = Route.DRYER } }) {
                HomeContent(state, commandState, model.busy, link, model.presets, model.presetMessage, HomeActions(
                    choose = { draft = StartDraft(); route = Route.WIZARD },
                    favorite = { preset -> StartDraft.from(preset)?.let { draft = it; route = Route.WIZARD } },
                    rename = { preset, name -> model.savePreset(name, preset.settings, preset.id) },
                    delete = { model.deletePreset(it.id) },
                    command = model::submit,
                    check = model::checkCommand,
                    wifiSettings = openWifi,
                    requestPermission = requestPermission,
                ))
            }
        }
        if (appearanceOpen) AppearanceDialog(appearance, onAppearance) { appearanceOpen = false }
    }
}

internal fun ConnectionProblem.messageResource() = when (this) {
    ConnectionProblem.INVALID_ADDRESS -> R.string.error_invalid_address
    ConnectionProblem.OFF_LINK -> R.string.error_off_link
    ConnectionProblem.PERMISSION_DENIED -> R.string.error_permission_denied
    ConnectionProblem.NO_WIFI -> R.string.error_no_wifi
    ConnectionProblem.WIFI_SELECTION_REQUIRED -> R.string.choose_wifi
    ConnectionProblem.NETWORK_LOST -> R.string.error_network_lost
    ConnectionProblem.TIMEOUT -> R.string.error_timeout
    ConnectionProblem.UNAVAILABLE -> R.string.error_unavailable
    ConnectionProblem.HTTP_STATUS -> R.string.error_http_status
    ConnectionProblem.RESPONSE_TOO_LARGE -> R.string.error_response_too_large
    ConnectionProblem.UNEXPECTED_DEVICE -> R.string.error_unexpected_device
    ConnectionProblem.INVALID_RESPONSE -> R.string.error_invalid_response
    ConnectionProblem.KEY_RECOVERY_FAILED -> R.string.error_key_recovery
    ConnectionProblem.DISCOVERY_UNAVAILABLE -> R.string.error_discovery
    ConnectionProblem.NO_CANDIDATES -> R.string.error_no_candidates
    ConnectionProblem.STORAGE_UNAVAILABLE -> R.string.error_storage
    ConnectionProblem.NO_PROFILE -> R.string.no_profile
}
