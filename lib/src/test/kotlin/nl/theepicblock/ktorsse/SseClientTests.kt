package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SseClientTests {
    @Test fun basicSse() {
        runBlocking {
            SseTestServer().use { server ->
                val client = HttpClient() {
                    install(SSE)
                }
                var receivedData: String? = null
                val listener = createSseListener(client, server.url) {
                    listener = {
                        receivedData = it.data
                    }
                }
                assertEquals(1, server.listenerCount)
                assertEquals(null, receivedData)
                server.send("FlorbleDorble")
                delay(200)
                assertEquals("FlorbleDorble", receivedData)
                listener.close()
            }
        }
    }
}
