package ru.bsv.candycmd.connection

import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SingleCommandWriterTest {
    private class FakeSocket(response: String) : Socket() {
        var connections = 0
        var closed = false
        val output = ByteArrayOutputStream()
        var input: InputStream = ByteArrayInputStream(response.encodeToByteArray())
        override fun connect(endpoint: SocketAddress, timeout: Int) { connections++ }
        override fun setSoTimeout(timeout: Int) {}
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun close() { closed = true; input.close() }
    }
    private val path = "/http-write.json?encrypted=1&data=ABCD"

    @Test fun oneRequestAndNoRedirectOrRetryAfterFailures() = runBlocking {
        for (response in listOf("HTTP/1.1 200 OK\r\n", "HTTP/1.1 302 Found\r\n", "HTTP/1.1 500 Error\r\n", "", "X".repeat(300))) {
            val socket = FakeSocket(response)
            if (response.contains("200")) SingleCommandWriter().send("192.0.2.42", path, socket)
            else assertFails { SingleCommandWriter().send("192.0.2.42", path, socket) }
            assertEquals(1, socket.connections)
            assertEquals("GET $path HTTP/1.1\r\nHost: 192.0.2.42\r\nConnection: close\r\n\r\n", socket.output.toString("US-ASCII"))
            assertTrue(socket.closed)
        }
    }

    @Test fun cancellationClosesBlockedSocketWithoutSecondWrite() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val socket = FakeSocket("").apply {
            input = object : InputStream() {
                override fun read(): Int {
                    started.complete(Unit)
                    check(closed.await(2, TimeUnit.SECONDS))
                    return -1
                }
                override fun close() { closed.countDown() }
            }
        }
        val task = launch { SingleCommandWriter().send("192.0.2.42", path, socket) }
        withTimeout(2_000) { started.await() }
        task.cancelAndJoin()
        assertEquals(1, socket.connections)
        assertTrue(socket.closed)
    }
}
