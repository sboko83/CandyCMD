package ru.bsv.candycmd.connection

import kotlinx.coroutines.*
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** Platform-independent HTTP policy; the caller supplies an already selected Wi-Fi route. */
internal class BoundedHttpReader(
    private val timeoutMillis: Long = 6_000,
    private val trace: (String) -> Unit = {},
) {
    suspend fun read(open: () -> HttpURLConnection): ByteArray = withTimeout(timeoutMillis) {
        val http = open()
        try {
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.connectTimeout = 3_000
            http.readTimeout = 3_000
            http.requestMethod = "GET"
            http.setRequestProperty("Connection", "close")
            closingIo({ http.disconnect() }) {
                trace("GET ${http.url.file}")
                val responseCode = http.responseCode
                trace("HTTP $responseCode")
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw ConnectionException(ConnectionProblem.HTTP_STATUS)
                }
                if (http.contentLengthLong > MAX_BODY) throw ConnectionException(ConnectionProblem.RESPONSE_TOO_LARGE)
                http.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > MAX_BODY) throw ConnectionException(ConnectionProblem.RESPONSE_TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } finally {
            http.disconnect()
            trace("END")
        }
    }

    private companion object { const val MAX_BODY = 64 * 1024 }
}

/** Closing unblocks pending IO before coroutineScope joins the cancelled worker. */
internal suspend fun <T> closingIo(close: () -> Unit, block: () -> T): T = try {
    coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        try { work.await() } finally { close() }
    }
} catch (error: Exception) {
    // Closing a cancelled socket may throw IOException in the worker; retain cancellation semantics.
    currentCoroutineContext().ensureActive()
    throw error
}
