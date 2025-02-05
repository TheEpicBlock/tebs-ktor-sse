package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration

fun HttpClient.launchSseListener(urlString: String? = null, builder: SseListenerBuilder.() -> Unit): Job {
    return launch {
        createSseListener(urlString, builder)
    }
}

suspend fun HttpClient.createSseListener(urlString: String? = null, builder: SseListenerBuilder.() -> Unit) {
    val builderData = SseListenerBuilder()
    builder(builderData)

    val statement = prepareRequest {
        if (urlString != null) {
            url(urlString)
        }
        builderData.requestBuilder?.let { it(this) }
    }
    statement.body<ByteReadChannel, Unit> { channel ->
        builderData.onConnected()
        // SSE parsing.
        // See: https://www.w3.org/TR/2012/WD-eventsource-20120426/#parsing-an-event-stream
        val data = StringBuilder()
        val comment = StringBuilder()
        var event: String? = null
        var id: String? = null // last event ID
        var retry: Long? = null

        while (true) {
            var line = channel.readUTF8Line()
            if (line == null) break

            if (line.isEmpty()) {
                // Dispatch the event
                builderData.listener(ServerSentEvent(
                    data = data.toString(),
                    event = event,
                    comments = comment.toString(),
                    retry = retry,
                    id = id,
                ))
                data.clear()
                comment.clear()
                event = null
                retry = null
            } else {
                val colonIndex = line.indexOf(':')

                var field: String
                var value: String
                if (colonIndex == -1) {
                    // the line is not empty but does not contain a U+003A COLON character (:)
                    // According to spec we must interpret the line as a field, and leave the value empty
                    field = line
                    value = ""
                } else {
                    // the line contains a U+003A COLON character
                    field = line.substring(0, colonIndex)
                    var valueStart = colonIndex+1
                    if (valueStart < line.length && line[valueStart] == ' ') valueStart++
                    value = line.substring(valueStart, line.length)
                }

                // Process the field/value pair
                when (field) {
                    "event" -> event = value
                    "data" -> data.append(value)
                    "id" -> id = value
                    "retry" -> value.toLongOrNull()?.let { retry = it }
                    // We'll also record comment fields, to match ktor's native sse handling
                    "" -> comment.append(value)
                    // Ignore any other values
                }
            }
        }
    }
}

class SseListenerBuilder {
    var requestBuilder: (HttpRequestBuilder.() -> Unit)? = null
    var listener: ((ServerSentEvent) -> Unit) = {}
    var onConnected: (() -> Unit) = {}
    var defaultReconnectionTime: Duration? = null
}