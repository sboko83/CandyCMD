package ru.bsv.candycmd

import android.app.Application
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import ru.bsv.candycmd.connection.AndroidDeviceNetwork
import ru.bsv.candycmd.connection.EncryptedProfileStore
import ru.bsv.candycmd.connection.LocalControlStore
import ru.bsv.candycmd.shared.control.*
import java.util.UUID
import ru.bsv.candycmd.shared.connection.*
import ru.bsv.candycmd.shared.protocol.DryerStatus
import ru.bsv.candycmd.shared.monitor.StatusMonitor
import ru.bsv.candycmd.shared.monitor.SerialSession

/** One retained owner for transport, foreground operations and polling across Activity recreation. */
class DryerViewModel(application: Application) : AndroidViewModel(application) {
    val network = AndroidDeviceNetwork(application)
    private val connection = DeviceConnection(network, EncryptedProfileStore(application))
    // Routine polling also lets an unconfirmed command settle; reconciliation only reads.
    val monitor: StatusMonitor = StatusMonitor({ pollStatus() }, System::currentTimeMillis)
    private val controlStore = LocalControlStore(application)
    val controller: CommandController = CommandController(connection::restore, network, controlStore, System::currentTimeMillis,
        observe = { monitor.reset(it) })
    private val scope = MainScope()
    private val session = SerialSession(scope)
    private var foreground = false
    private var initialized = false
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var ip by mutableStateOf("")
    var candidates by mutableStateOf(emptyList<Candidate>())
        private set
    var networks by mutableStateOf(emptyList<Network>())
        private set
    var selected by mutableStateOf<Network?>(null)
        private set
    var showMonitor by mutableStateOf(false)
        private set
    /** True until the first restore finishes, so a saved dryer never flashes the setup screen. */
    var restoring by mutableStateOf(true)
        private set
    var hasProfile by mutableStateOf(false)
        private set
    var presets by mutableStateOf(emptyList<Preset>())
        private set
    var presetMessage by mutableStateOf("")
        private set
    var presetsReady by mutableStateOf(false)
        private set
    var savingPreset by mutableStateOf(false)
        private set
    /** Null until read: an unreadable file hides the stars instead of being overwritten. */
    var favoritePrograms by mutableStateOf<Set<String>?>(null)
        private set
    private var storedFavoritePrograms = emptySet<String>()
    private var favoriteProgramWrites = 0
    var permissionGranted by mutableStateOf(network.hasPermission())
        private set
    var refinding by mutableStateOf(false)
        private set
    var refindFailed by mutableStateOf(false)
        private set
    private var consecutiveFailures = 0
    private var lastRefindAttempt = 0L
    private var refindJob: Job? = null

    /** A poll failure that looks like a moved dryer arms the automatic re-search; success disarms it. */
    private suspend fun pollStatus(): DryerStatus {
        try {
            val device = connection.restore()
            controller.reconcile(device)
            consecutiveFailures = 0
            refindFailed = false
            return device.status
        } catch (error: ConnectionException) {
            noteReadFailure(error.problem)
            throw error
        }
    }

    private fun noteReadFailure(problem: ConnectionProblem) {
        if (problem !in refindProblems) return
        consecutiveFailures++
        if (consecutiveFailures < REFIND_AFTER_FAILURES) return
        if (System.currentTimeMillis() - lastRefindAttempt < REFIND_RETRY_MILLIS) return
        startRefind()
    }

    /** Runs outside the connection mutex and the session: the monitor keeps polling the old address meanwhile. */
    private fun startRefind() {
        if (!foreground || busy || refinding || !hasProfile || !showMonitor) return
        refinding = true
        refindFailed = false
        lastRefindAttempt = System.currentTimeMillis()
        refindJob = scope.launch {
            try {
                val device = connection.reconnect()
                ip = device.profile.address.value
                consecutiveFailures = 0
                refindFailed = false
                message = text(R.string.refind_success)
                monitor.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ConnectionException) { refindFailed = true }
            finally { refinding = false }
        }
    }

    private val refindProblems = setOf(ConnectionProblem.TIMEOUT, ConnectionProblem.UNAVAILABLE,
        ConnectionProblem.HTTP_STATUS, ConnectionProblem.RESPONSE_TOO_LARGE,
        ConnectionProblem.UNEXPECTED_DEVICE, ConnectionProblem.INVALID_RESPONSE)

    fun start() {
        foreground = true
        refreshNetworks()
        if (!initialized) {
            initialized = true
            scope.launch {
                try { presets = controlStore.loadPresets(); presetsReady = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { presetMessage = text(R.string.presets_read_error) }
                try { favoritePrograms = controlStore.loadFavoritePrograms().also { storedFavoritePrograms = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {}
            }
            restore()
        // A restore cancelled by going to the background is retried instead of falling back to setup.
        } else if (restoring) restore()
        else if (showMonitor) replaceSession { monitor.run() }
    }

    fun stop() {
        foreground = false
        session.cancel()
        refindJob?.cancel()
        refinding = false
        busy = false
        candidates = emptyList()
    }

    fun refreshNetworks() {
        networks = network.wifiNetworks()
        permissionGranted = network.hasPermission()
    }

    fun networkLost(lost: Network) {
        refreshNetworks()
        if (network.lastUsedNetwork != lost) return
        monitor.invalidate(ConnectionProblem.NETWORK_LOST)
        message = text(R.string.error_network_lost)
        restartMonitoring()
    }

    fun permissionDenied() {
        permissionGranted = false
        message = text(R.string.error_permission_denied)
    }

    fun selectNetwork(value: Network?) {
        selected = value
        network.selectedNetwork = value
        candidates = emptyList()
        message = text(R.string.choose_wifi)
        monitor.invalidate(ConnectionProblem.NETWORK_LOST)
        restartMonitoring()
    }

    private fun restartMonitoring() {
        session.cancel()
        busy = false
        if (foreground && showMonitor) replaceSession { monitor.run() }
    }

    fun openConnection() {
        if (busy) return
        showMonitor = false
        session.cancel()
        busy = false
    }

    fun openMonitor() {
        if (busy) return
        showMonitor = true
        replaceSession { monitor.run() }
    }

    fun submit(command: DryerCommand) {
        // Cancel stays available after an unconfirmed command: it only returns the machine to idle.
        if (!foreground || busy || controller.state.value.locked && command !is DryerCommand.Cancel) return
        commandOperation { controller.execute(command) }
    }

    fun checkCommand() {
        if (!foreground || busy) return
        commandOperation { controller.checkIdle() }
    }

    private fun commandOperation(block: suspend () -> Unit) {
        busy = true
        replaceSession {
            try { block() }
            finally { if (session.job === currentCoroutineContext()[Job]) busy = false }
            if (showMonitor) monitor.run()
        }
    }

    fun start(settings: Settings, favoriteName: String?) {
        favoriteName?.let { savePreset(it, settings) }
        submit(DryerCommand.Start(settings))
    }

    fun savePreset(name: String, settings: Settings, id: String? = null) {
        val trimmed = name.trim()
        if (!presetsReady || savingPreset || settings.recipe() == null || trimmed.isEmpty() ||
            trimmed.length > 60 || trimmed.any(Char::isISOControl)) return
        val value = Preset(id ?: UUID.randomUUID().toString(), trimmed, settings)
        updatePresets(if (id == null) presets + value else presets.map { if (it.id == id) value else it })
    }

    fun deletePreset(id: String) {
        if (presetsReady && !savingPreset) updatePresets(presets.filterNot { it.id == id })
    }

    private fun updatePresets(values: List<Preset>) {
        savingPreset = true
        scope.launch {
            try {
                controlStore.savePresets(values)
                presets = values
                presetMessage = text(R.string.presets_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { presetMessage = text(R.string.presets_write_error) }
            finally { savingPreset = false }
        }
    }

    /** The star flips at once; a failed write rolls back to the stored set unless a newer toggle followed. */
    fun toggleFavoriteProgram(id: String) {
        val current = favoritePrograms ?: return
        val values = if (id in current) current - id else current + id
        favoritePrograms = values
        val write = ++favoriteProgramWrites
        scope.launch {
            try {
                controlStore.saveFavoritePrograms(values)
                storedFavoritePrograms = values
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (write == favoriteProgramWrites) favoritePrograms = storedFavoritePrograms }
        }
    }

    fun connect(candidate: Candidate? = null) = operate {
        connected(connection.connect(candidate?.address?.value ?: ip.trim(), candidate))
    }

    fun restore() {
        permissionGranted = network.hasPermission()
        operate {
            try {
                controller.restore()
                connected(connection.restore())
                restoring = false
            } catch (error: ConnectionException) {
                restoring = false
                // A saved dryer that is asleep or out of reach keeps the home screen and keeps polling.
                if (error.problem !in setupProblems) {
                    hasProfile = true
                    showMonitor = true
                    monitor.invalidate(error.problem)
                }
                throw error
            }
        }
    }

    fun discover() = operate {
        candidates = emptyList()
        message = text(R.string.searching)
        candidates = connection.discover()
        message = text(R.string.choose_candidate)
    }

    fun forget() = operate {
        controlStore.savePresets(emptyList())
        presets = emptyList()
        presetsReady = true
        presetMessage = ""
        controlStore.saveFavoritePrograms(emptySet())
        storedFavoritePrograms = emptySet()
        favoritePrograms = emptySet()
        connection.forget()
        hasProfile = false
        monitor.reset()
        consecutiveFailures = 0
        refindFailed = false
        ip = ""
        candidates = emptyList()
        showMonitor = false
        message = text(R.string.profile_deleted)
    }

    fun cancelOperation() {
        session.cancel()
        busy = false
        message = text(R.string.cancelled)
    }

    private fun connected(device: ConnectedDevice) {
        ip = device.profile.address.value
        candidates = emptyList()
        monitor.reset(device.status)
        hasProfile = true
        showMonitor = true
        message = text(R.string.connection_ok)
    }

    private fun operate(block: suspend () -> Unit) {
        if (!foreground || busy) return
        busy = true
        replaceSession {
            message = text(R.string.connecting)
            try { block() }
            catch (error: ConnectionException) { message = text(error.problem.messageResource()) }
            finally { if (session.job === currentCoroutineContext()[Job]) busy = false }
            if (showMonitor) monitor.run(delayFirstRead = true)
        }
    }

    private fun replaceSession(block: suspend () -> Unit) {
        session.replace {
            if (foreground) block()
        }
    }

    private val setupProblems = setOf(ConnectionProblem.NO_PROFILE, ConnectionProblem.PERMISSION_DENIED,
        ConnectionProblem.STORAGE_UNAVAILABLE)

    private fun text(id: Int) = getApplication<Application>().getString(id)

    private companion object {
        const val REFIND_AFTER_FAILURES = 3
        const val REFIND_RETRY_MILLIS = 90_000L
    }

    override fun onCleared() { scope.cancel() }
}
