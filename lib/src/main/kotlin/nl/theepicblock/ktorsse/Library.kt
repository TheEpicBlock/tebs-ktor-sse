package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun HttpClient.launchSseListener(urlString: String? = null, builder: SseListenerBuilder.() -> Unit): Job {
    return launch {
        createSseListener(urlString, builder)
    }
}

suspend fun HttpClient.createSseListener(urlString: String? = null, builder: SseListenerBuilder.() -> Unit) {
    val builderData = SseListenerBuilder()
    builder(builderData)

    // Delay corresponds to https://www.w3.org/TR/2012/WD-eventsource-20120426/#concept-event-stream-reconnection-time
    var delay = builderData.defaultReconnectionTime?.toMillis()
    // lastEventId corresponds to https://www.w3.org/TR/2012/WD-eventsource-20120426/#concept-event-stream-last-event-id
    var lastEventId: String? = null // last event ID
    // Amount of concurrent retries to connect
    var retryCount = 0

    val statement = prepareRequest {
        if (urlString != null) {
            url(urlString)
        }
        builderData.requestBuilder?.let { it(this) }
    }
    while (isActive) {
        statement.also {
            headers {
                if (lastEventId == null) {
                    remove("Last-Event-ID")
                } else {
                    set("Last-Event-ID", lastEventId!!)
                }
            }
        }
        statement.body<ByteReadChannel, Unit> { channel ->
            builderData.onConnected()
            retryCount = 0
            // SSE parsing.
            // See: https://www.w3.org/TR/2012/WD-eventsource-20120426/#parsing-an-event-stream
            var data: StringBuilder? = null
            var comment: StringBuilder? = null
            var event: String? = null
            var retry: Long? = null

            while (isActive) {
                val line = channel.readUTF8Line() ?: break

                if (line.isEmpty()) {
                    // Dispatch the event
                    // Intentionally ignoring spec here by emitting events even if the data field is empty
                    builderData.listener(
                        ServerSentEvent(
                            data = data?.toString(),
                            event = event,
                            comments = comment?.toString(),
                            retry = retry,
                            id = lastEventId,
                        )
                    )
                    data = null
                    comment = null
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
                        var valueStart = colonIndex + 1
                        if (valueStart < line.length && line[valueStart] == ' ') valueStart++
                        value = line.substring(valueStart, line.length)
                    }

                    // Process the field/value pair
                    when (field) {
                        "event" -> event = value
                        "data" -> {
                            if (data == null) data = StringBuilder()
                            if (data.isNotEmpty()) data.append("\u000A")
                            data.append(value)
                        }
                        "id" -> lastEventId = if (value == "" ) null else value
                        "retry" -> value.toLongOrNull()?.let {
                            retry = it
                            delay = it // According to spec, we should set our reconnection time immediately upon reading this field
                        }
                        // We'll also record comment fields, to match ktor's native sse handling
                        "" -> {
                            if (comment == null) comment = StringBuilder()
                            if (comment.isNotEmpty()) comment.append("\u000A")
                            comment.append(value)
                        }
                        // Ignore any other values
                    }
                }
            }
        }
        builderData.onDisconnected()
        val retryData = RetryHandler(delay, retryCount)
        builderData.retryHandler(retryData)
        retryCount++
        if (!retryData.answered) {
            throw IllegalStateException("Must call retry() or dontRetry()")
        }
        if (retryData.retryMillis == -1L) {
            break
        } else {
            delay(retryData.retryMillis)
        }
    }
}

class SseListenerBuilder {
    var requestBuilder: (HttpRequestBuilder.() -> Unit)? = null
    var listener: ((ServerSentEvent) -> Unit) = {}
    var onConnected: (() -> Unit) = {}
    var onDisconnected: (() -> Unit) = {}
    var defaultReconnectionTime: java.time.Duration? = null
    var retryHandler: (RetryHandler.() -> Unit) = {
        retry(retryDelay ?: 5_000)
    }
}

class RetryHandler(
    /**
     * The amount of time that the server has requested we delay the retry for
     */
    val retryDelay: Long?,
    /**
     * Incremented for each concurrent retry. Will be zero on the first retry
     */
    val retryCount: Int
) {
    internal var answered: Boolean = false
    internal var retryMillis: Long = -1

    fun retry(milliseconds: Long) {
        answered = true
        retryMillis = milliseconds
    }

    fun dontRetry() {
        answered = true
        retryMillis = -1
    }
}