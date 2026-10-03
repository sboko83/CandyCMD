package ru.bsv.candycmd.connection

import kotlinx.coroutines.*
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BoundedHttpReaderTest {
    private class Http(
        val body: ByteArray = "{}".encodeToByteArray(),
        val code: Int = 200,
        val declaredLength: Long = body.size.toLong(),
    ) : HttpURLConnection(URL("http://example.invalid/http-read.json?encrypted=1")) {
        var disconnected = false
        var openedBody = false
        var source: InputStream = ByteArrayInputStream(body)
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; source.close() }
        override fun getResponseCode() = code
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream(): InputStream { openedBody = true; return source }
    }

    @Test fun readsWithSafePolicyAndCloses() = runBlocking {
        val http = Http()
        assertContentEquals(http.body, BoundedHttpReader().read { http })
        assertEquals("GET", http.requestMethod)
        assertFalse(http.instanceFollowRedirects)
        assertFalse(http.useCaches)
        assertEquals("close", http.getRequestProperty("Connection"))
        assertEquals(3_000, http.readTimeout)
        assertEquals(3_000, http.connectTimeout)
        assertTrue(http.disconnected)
    }

    @Test fun redirectsAndErrorsAreRejectedBeforeReadingBody() = runBlocking {
        for (code in listOf(301, 302, 307, 308, 401, 404, 500)) {
            val http = Http(code = code)
            assertEquals(ConnectionProblem.HTTP_STATUS,
                assertFailsWith<ConnectionException> { BoundedHttpReader().read { http } }.problem)
            assertFalse(http.openedBody)
            assertFalse(http.instanceFollowRedirects)
            assertTrue(http.disconnected)
        }
    }

    @Test fun declaredAndStreamingOversizeResponsesAreRejected() = runBlocking {
        for (length in listOf(-1L, 65_537L)) {
            val http = Http(body = ByteArray(65_537), declaredLength = length)
            assertEquals(ConnectionProblem.RESPONSE_TOO_LARGE,
                assertFailsWith<ConnectionException> { BoundedHttpReader().read { http } }.problem)
            assertTrue(http.disconnected)
            assertEquals(length == -1L, http.openedBody)
        }
        assertEquals(65_536, BoundedHttpReader().read { Http(ByteArray(65_536)) }.size)
    }

    @Test fun cancellationClosesBlockedRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val http = Http().apply {
            source = object : InputStream() {
                override fun read(): Int {
                    started.complete(Unit)
                    check(closed.await(2, TimeUnit.SECONDS)) { "Cancellation did not close the connection" }
                    return -1
                }
                override fun close() { closed.countDown() }
            }
        }
        val task = launch { BoundedHttpReader().read { http } }
        withTimeout(2_000) { started.await() }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(http.disconnected)
    }

    @Test fun totalDeadlineClosesBlockedRead() = runBlocking {
        val closed = CountDownLatch(1)
        val http = Http().apply {
            source = object : InputStream() {
                override fun read(): Int {
                    check(closed.await(2, TimeUnit.SECONDS)) { "Deadline did not close the connection" }
                    return -1
                }
                override fun close() { closed.countDown() }
            }
        }
        assertFailsWith<TimeoutCancellationException> { BoundedHttpReader(100).read { http } }
        assertTrue(http.disconnected)
    }
}
