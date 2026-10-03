package ru.bsv.candycmd.shared.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Replacement waits for cancellation cleanup, even when intermediate replacements are cancelled. */
class SerialSession(private val scope: CoroutineScope) {
    private val gate = Mutex()
    var job: Job? = null
        private set

    fun replace(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { gate.withLock { block() } }
    }

    fun cancel() { job?.cancel() }
}
