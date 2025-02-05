package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SseClientTests {
    @Test
    fun basicSse() {
        runBlocking {
            withTimeout(2000) {
                SseTestServer().use { server ->
                    val client = HttpClient()
                    val listener = testListener(client, server.url)
                    listener.awaitConnected()
                    assertEquals(1, server.listenerCount)
                    assertNoData(listener)
                    server.send("FlorbleDorble")
                    delay(200)
                    assertEquals("FlorbleDorble", listener.awaitNextEvent().data)
                    assertNoData(listener)
                    listener.cancel()
                }
            }
        }
    }

    @Test
    fun reconnect() {
        runBlocking {
            withTimeout(5000) {
                val server = SseTestServer()
                val client = HttpClient()
                val listener = testListener(client, server.url)
                listener.awaitConnected()
                assertEquals(1, server.listenerCount)
                server.send("Test1")
                delay(200)
                assertEquals("Test1", listener.awaitNextEvent().data)
                assertNoData(listener)

                // Restart server
                server.close()
                listener.awaitDisconnection()
                val server2 = SseTestServer(server.port)
                listener.awaitConnected()
                assertNoData(listener)
                assertEquals(1, server2.listenerCount)
                server2.send("Test2")
                assertEquals("Test2", listener.awaitNextEvent().data)
                server2.close()

                listener.cancel()
            }
        }
    }

    fun testListener(client: HttpClient, url: String): TestListener {
        val sseEvents = Channel<ServerSentEvent>()
        val conEvents = Channel<Boolean>()
        val job = client.launchSseListener(url) {
            onConnected = {
                println("Listener: connected")
                conEvents.trySendBlocking(true)
            }
            onDisconnected = {
                println("Listener: disconnected")
                conEvents.trySendBlocking(false)
            }
            listener = {
                println("Listener: received event")
                sseEvents.trySendBlocking(it)
            }
            defaultReconnectionTime = Duration.ofMillis(200)
        }
        return TestListener(sseEvents, conEvents, job)
    }

    fun assertNoData(listener: TestListener) {
        listener.assertNoData()
    }

    class TestListener(
        private var sseEvents: Channel<ServerSentEvent>,
        private val connectionEvents: Channel<Boolean>,
        val job: Job
    ) {
        suspend fun awaitConnected() {
            val next = connectionEvents.receive()
            assertTrue(next)
        }

        suspend fun awaitDisconnection() {
            val next = connectionEvents.receive()
            assertFalse(next)
        }

        suspend fun awaitNextEvent(): ServerSentEvent {
            return sseEvents.receive()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun assertNoData() {
            assert(sseEvents.isEmpty)
        }

        fun cancel() {
            job.cancel()
        }
    }
}
