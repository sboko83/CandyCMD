package ru.bsv.candycmd.connection

import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Raw HTTP deliberately avoids automatic retry of GET by HTTP connection pools. */
internal class SingleCommandWriter {
    suspend fun send(host: String, path: String, socket: Socket) {
        try { withTimeout(6_000) {
            require(path.matches(Regex("/http-write\\.json\\?encrypted=1&data=[0-9A-F]+")))
            closingIo({ socket.close() }) {
                socket.soTimeout = 3_000
                socket.connect(InetSocketAddress(host, 80), 3_000)
                val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                val input = socket.getInputStream()
                val line = StringBuilder()
                var complete = false
                while (line.length < 256) {
                    val byte = input.read()
                    if (byte < 0) throw IOException("Missing status")
                    if (byte == 10) { complete = true; break }
                    line.append(byte.toChar())
                }
                if (!complete || !line.toString().trimEnd('\r').matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) {
                    throw IOException("Unconfirmed response")
                }
            }
        } } finally { socket.close() }
    }
}
