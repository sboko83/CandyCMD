package ru.bsv.candycmd.shared.control

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import ru.bsv.candycmd.shared.connection.ConnectedDevice
import ru.bsv.candycmd.shared.connection.DeviceProfile
import ru.bsv.candycmd.shared.protocol.DryerStatus

interface CommandTransport {
    /** Exactly one HTTP request; no retry, redirect or cloud fallback. */
    suspend fun send(profile: DeviceProfile, command: PreparedCommand)
}

interface PendingCommandStore {
    suspend fun isPending(): Boolean
    suspend fun setPending(pending: Boolean)
}

enum class CommandPhase { IDLE, CHECKING, SENDING, WAITING, CONFIRMED, REJECTED, UNKNOWN }
data class CommandState(val phase: CommandPhase = CommandPhase.IDLE, val block: CommandBlock? = null) {
    val busy: Boolean get() = phase in setOf(CommandPhase.CHECKING, CommandPhase.SENDING, CommandPhase.WAITING)
    val locked: Boolean get() = busy || phase == CommandPhase.UNKNOWN
}

/** One explicit submission. A durable marker survives process death at any point after preflight. */
class CommandController(
    private val read: suspend () -> ConnectedDevice,
    private val transport: CommandTransport,
    private val pending: PendingCommandStore,
    private val nowMillis: () -> Long,
    private val observe: (DryerStatus) -> Unit = {},
) {
    private val gate = Mutex()
    private var lastSent: PreparedCommand? = null
    private var lastProfile: DeviceProfile? = null
    private var uncertainSince: Long? = null
    private val mutableState = MutableStateFlow(CommandState())
    val state = mutableState.asStateFlow()

    suspend fun restore() {
        if (!gate.tryLock()) return
        try {
            if (pending.isPending()) {
                uncertainSince = nowMillis()
                mutableState.value = CommandState(CommandPhase.UNKNOWN)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = CommandState(CommandPhase.UNKNOWN, CommandBlock.STORAGE) }
        finally { gate.unlock() }
    }

    suspend fun execute(command: DryerCommand) {
        if (!gate.tryLock()) return
        var uncertain = false
        // Cancel only returns the machine to idle, so an unconfirmed earlier command never blocks it.
        val recovering = command is DryerCommand.Cancel && state.value.phase == CommandPhase.UNKNOWN
        try {
            if (state.value.busy || !recovering && (state.value.locked || pending.isPending())) {
                mutableState.value = CommandState(CommandPhase.UNKNOWN)
                return
            }
            mutableState.value = CommandState(CommandPhase.CHECKING)
            val before = read()
            observe(before.status)
            val block = CommandPolicy.blocked(command, before.status)
                ?: if (before.profile.keyBytes()?.size != 16) CommandBlock.NO_KEY else null
            if (block != null) {
                mutableState.value = CommandState(if (recovering) CommandPhase.UNKNOWN else CommandPhase.REJECTED, block)
                return
            }
            val prepared = CommandBuilder.prepare(command, nowMillis())
            // Set before suspension: cancellation during durable storage must also stay conservative.
            uncertain = true
            pending.setPending(true)
            lastSent = prepared
            lastProfile = before.profile
            uncertainSince = nowMillis()
            mutableState.value = CommandState(CommandPhase.SENDING)
            try { transport.send(before.profile, prepared) }
            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A lost reply can still have changed the appliance. Read only. */ }
            mutableState.value = CommandState(CommandPhase.WAITING)
            val confirmed = withTimeoutOrNull(22_000) {
                repeat(4) {
                    delay(3_000)
                    try {
                        val after = read()
                        if (!sameProfile(before.profile, after.profile)) return@repeat
                        observe(after.status)
                        if (prepared.confirmed(after.status)) return@withTimeoutOrNull true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Bounded read-only observation. */ }
                }
                false
            } == true
            if (confirmed) {
                pending.setPending(false)
                uncertain = false
                mutableState.value = CommandState(CommandPhase.CONFIRMED)
            } else mutableState.value = CommandState(CommandPhase.UNKNOWN)
        } catch (cancelled: CancellationException) {
            mutableState.value = CommandState(if (uncertain) CommandPhase.UNKNOWN else CommandPhase.IDLE)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = CommandState(if (uncertain) CommandPhase.UNKNOWN else CommandPhase.REJECTED, CommandBlock.CONNECTION)
        } finally { gate.unlock() }
    }

    /**
     * Read-only reconciliation from routine polling; never sends. A late transition that matches the sent command
     * confirms it. After [SETTLE_MILLIS] nothing is still applying: idle or a recognised app cycle releases the lock,
     * because a new start stays blocked by [CommandPolicy] until the machine is idle again.
     */
    suspend fun reconcile(device: ConnectedDevice) {
        if (state.value.phase != CommandPhase.UNKNOWN || !gate.tryLock()) return
        try {
            if (state.value.phase != CommandPhase.UNKNOWN) return
            if (lastProfile?.let { sameProfile(it, device.profile) } == false) return
            val confirmed = lastSent?.confirmed(device.status) == true
            val settled = CommandPolicy.safe(device.status) &&
                (CommandPolicy.idle(device.status) || CycleTarget.from(device.status) != null) &&
                nowMillis() - (uncertainSince ?: return) >= SETTLE_MILLIS
            if (!confirmed && !settled) return
            pending.setPending(false)
            lastSent = null
            uncertainSince = null
            mutableState.value = CommandState(if (confirmed) CommandPhase.CONFIRMED else CommandPhase.IDLE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Storage failure keeps the conservative lock. */ }
        finally { gate.unlock() }
    }

    private fun sameProfile(before: DeviceProfile, after: DeviceProfile): Boolean =
        before.address.value == after.address.value && before.mac == after.mac &&
            before.keyBytes().contentEquals(after.keyBytes())

    companion object { const val SETTLE_MILLIS = 30_000L }

    /** Explicit recovery never retries. Only observed idle permits another new start. */
    suspend fun checkIdle() {
        if (!gate.tryLock()) return
        try {
            mutableState.value = CommandState(CommandPhase.CHECKING)
            val device = read()
            observe(device.status)
            if (CommandPolicy.safe(device.status) && CommandPolicy.idle(device.status)) {
                pending.setPending(false)
                mutableState.value = CommandState()
            } else mutableState.value = CommandState(CommandPhase.UNKNOWN)
        } catch (cancelled: CancellationException) {
            mutableState.value = CommandState(CommandPhase.UNKNOWN)
            throw cancelled
        } catch (_: Exception) { mutableState.value = CommandState(CommandPhase.UNKNOWN) }
        finally { gate.unlock() }
    }
}
