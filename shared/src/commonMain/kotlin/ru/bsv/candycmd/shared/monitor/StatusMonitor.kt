package ru.bsv.candycmd.shared.monitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import ru.bsv.candycmd.shared.protocol.DryerStatus

data class MonitorState(
    val status: DryerStatus? = null,
    val lastSuccessMillis: Long? = null,
    val problem: ConnectionProblem? = null,
    val active: Boolean = false,
    val refreshing: Boolean = false,
    val stale: Boolean = true,
)

/** Confine run/refresh calls to the UI dispatcher. The caller owns the foreground lifetime. */
class StatusMonitor(
    private val read: suspend () -> DryerStatus,
    private val nowMillis: () -> Long,
    private val intervalMillis: Long = 5_000,
    private val maxBackoffMillis: Long = 30_000,
) {
    init { require(intervalMillis > 0 && maxBackoffMillis >= intervalMillis) }

    private val mutableState = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = mutableState.asStateFlow()
    private val owner = Mutex()
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    /** Requests during an active read are already covered by that read; never queue another. */
    fun refresh(): Boolean = state.value.active && !state.value.refreshing && refreshes.trySend(Unit).isSuccess

    /** Call only after the previous loop has joined, when the selected profile changes. */
    fun reset(status: DryerStatus? = null) {
        check(!state.value.active)
        mutableState.value = MonitorState(status, status?.let { nowMillis() }, stale = status == null)
    }

    fun invalidate(problem: ConnectionProblem) {
        mutableState.value = state.value.copy(problem = problem, stale = true)
    }

    /** A second lifecycle owner cannot create a second loop. Cancellation retains stale last data. */
    suspend fun run(delayFirstRead: Boolean = false) {
        if (!owner.tryLock()) return
        var nextDelay = intervalMillis
        mutableState.value = state.value.copy(active = true)
        try {
            if (delayFirstRead) withTimeoutOrNull(intervalMillis) { refreshes.receive() }
            while (true) {
                currentCoroutineContext().ensureActive()
                refreshes.tryReceive()
                mutableState.value = state.value.copy(refreshing = true)
                try {
                    val status = read()
                    currentCoroutineContext().ensureActive()
                    mutableState.value = MonitorState(status, nowMillis(), active = true, stale = false)
                    nextDelay = intervalMillis
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ConnectionException) {
                    mutableState.value = state.value.copy(problem = error.problem, refreshing = false, stale = true)
                    nextDelay = if (nextDelay > maxBackoffMillis / 2) maxBackoffMillis
                        else (nextDelay * 2).coerceAtMost(maxBackoffMillis)
                }
                withTimeoutOrNull(nextDelay) { refreshes.receive() }
            }
        } finally {
            mutableState.value = state.value.copy(active = false, refreshing = false, stale = true)
            refreshes.tryReceive()
            owner.unlock()
        }
    }
}
