package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTests {
    @Test
    fun spaceHandling() {
        testParser("data: abcd\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    data = "abcd"
                ),
                it[0]
            )
        }
        testParser("data:abcd\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    data = "abcd"
                ),
                it[0]
            )
        }
        testParser("data:  abcd\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    data = " abcd"
                ),
                it[0]
            )
        }
    }

    @Test
    fun multiData() {
        testParser("data: abcd\ndata: efgh\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    data = "abcd\nefgh"
                ),
                it[0]
            )
        }
    }

    @Test
    fun noEmpty() {
        // Needs to have an empty newline to trigger the event. No events should be triggered
        testParser("data: abcd\n") {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun noData() {
        // This is an intentional departure from the spec.
        // Normally you wouldn't get an event if there's no data, but to match
        // ktor's SSE we do emit an event here
        testParser("id: test\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    id = "test"
                ),
                it[0]
            )
        }
    }

    @Test
    fun doubleEvent() {
        testParser("event: test\nevent: test2\ndata: abcd\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    event = "test2",
                    data = "abcd"
                ),
                it[0]
            )
        }
    }

    @Test
    fun persistentId() {
        // The id field will persist until explicitly changed again
        testParser("id: testid\ndata: test1\n\ndata: test2\n\n") {
            assertEquals(2, it.size)
            assertSseEqual(
                ServerSentEvent(
                    id = "testid",
                    data = "test1"
                ),
                it[0]
            )
            assertSseEqual(
                ServerSentEvent(
                    id = "testid",
                    data = "test2"
                ),
                it[1]
            )
        }
    }

    @Test
    fun nonPersistentEvent() {
        // The event field is not persistent
        testParser("event: abcd\ndata: test1\n\ndata: test2\n\n") {
            assertEquals(2, it.size)
            assertSseEqual(
                ServerSentEvent(
                    event = "abcd",
                    data = "test1"
                ),
                it[0]
            )
            assertSseEqual(
                ServerSentEvent(
                    data = "test2"
                ),
                it[1]
            )
        }
    }

    @Test
    fun unknownField() {
        // Unknown fields should be ignored
        testParser("data: abcd\nbuttocks: test123\n\n") {
            assertEquals(1, it.size)
            assertSseEqual(
                ServerSentEvent(
                    data = "abcd"
                ),
                it[0]
            )
        }
    }

    fun testParser(input: String, checker: (List<ServerSentEvent>) -> Unit) {
        runBlocking {
            withTimeout(2000) {
                val events = ArrayList<ServerSentEvent>()
                val server = SseTestServer()
                val client = HttpClient()
                val listener = testListener(client, server.url) {
                    retryHandler = { dontRetry() }
                }
                listener.awaitConnected()
                server.sendRaw(input)
                val job = launch {
                    listener.sseEvents.consumeEach { events.add(it) }
                }
                server.close()
                listener.awaitDisconnection()
                job.cancel()
                checker(events)
            }
        }
    }
}

fun assertSseEqual(expected: ServerSentEvent, actual: ServerSentEvent) {
    assertEquals(expected.data, actual.data, "Data field does not match")
    assertEquals(expected.id, actual.id, "Id field does not match")
    assertEquals(expected.event, actual.event, "Event field does not match")
    assertEquals(expected.comments, actual.comments, "Comments field does not match")
    assertEquals(expected.retry, actual.retry, "Retry field does not match")
}
