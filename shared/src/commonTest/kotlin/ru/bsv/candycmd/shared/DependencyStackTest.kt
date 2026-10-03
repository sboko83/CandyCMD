package ru.bsv.candycmd.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DependencyStackTest {
    @Serializable
    private data class SyntheticStatus(val phase: String, val remaining: String? = null)

    @Test
    fun mockTransportFlowsThroughGeneratedSerializerOnHost() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/synthetic-status", request.url.encodedPath)
            respond("""{"phase":"NULL","unknown":"future-field"}""")
        }
        val client = HttpClient(engine)
        try {
            val json = Json { ignoreUnknownKeys = true }
            val status = flow {
                emit(json.decodeFromString<SyntheticStatus>(
                    client.get("https://example.invalid/synthetic-status").bodyAsText()
                ))
            }.first()
            assertEquals("NULL", status.phase)
            assertNull(status.remaining)
        } finally {
            client.close()
        }
    }
}
