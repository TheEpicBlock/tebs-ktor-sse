package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

fun HttpClient.launchSseListener(urlString: String? = null, configurer: SseListenerConfig.() -> Unit): Job {
    return launch {
        createSseListener(urlString, configurer)
    }
}

suspend fun HttpClient.createSseListener(urlString: String? = null, configurer: SseListenerConfig.() -> Unit) {
    val config = SseListenerConfig()
    configurer(config)

    // Delay corresponds to https://www.w3.org/TR/2012/WD-eventsource-20120426/#concept-event-stream-reconnection-time
    var delay = config.defaultReconnectionTime?.toMillis()
    // lastEventId corresponds to https://www.w3.org/TR/2012/WD-eventsource-20120426/#concept-event-stream-last-event-id
    var lastEventId: String? = null // last event ID
    // Amount of concurrent retries to connect
    var retryCount = 0
    var isFirstConnection = true

    val statement = prepareRequest {
        if (urlString != null) {
            url(urlString)
        }
        config.requestBuilder?.let { it(this) }
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
        var didConnect = false
        val error: SseConnectionError? = statement.execute { httpRequest ->
            if (!(httpRequest.contentType()?.contentType == "text" &&
                        httpRequest.contentType()?.contentSubtype == "event-stream")
            ) {
                return@execute SseInvalidContentType(httpRequest.contentType())
            }
            if (!httpRequest.status.isSuccess()) {
                return@execute SseServerError(httpRequest.status)
            }

            val channel = httpRequest.bodyAsChannel()
            config.onConnected()
            didConnect = true
            retryCount = 0
            // SSE parsing.
            // See: https://www.w3.org/TR/2012/WD-eventsource-20120426/#parsing-an-event-stream
            var data: StringBuilder? = null
            var comment: StringBuilder? = null
            var event: String? = null
            var retry: Long? = null

            while (isActive) {
                val line = channel.readUTF8Line() ?: return@execute null

                if (line.isEmpty()) {
                    // Dispatch the event
                    // Intentionally ignoring spec here by emitting events even if the data field is empty
                    config.listener(
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

                        "id" -> lastEventId = if (value == "") null else value
                        "retry" -> value.toLongOrNull()?.let {
                            retry = it
                            delay =
                                it // According to spec, we should set our reconnection time immediately upon reading this field
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
            return@execute null
        }
        if (didConnect) {
            config.onDisconnected()
        }
        if (!isActive) {
            break
        } else {
            val retryData = RetryHandler(delay, retryCount, (isFirstConnection && !didConnect), error)
            config.retryHandler(retryData)
            retryCount++
            if (!retryData.answered) {
                throw IllegalStateException("Must call retry() or dontRetry()")
            }
            if (retryData.retryMillis == -1L) {
                break
            } else {
                delay(retryData.retryMillis)
            }
            isFirstConnection = false
        }
    }
}

class SseListenerConfig {
    var requestBuilder: (HttpRequestBuilder.() -> Unit)? = null
    var listener: ((ServerSentEvent) -> Unit) = {}
    var onConnected: (() -> Unit) = {}
    var onDisconnected: (() -> Unit) = {}
    var defaultReconnectionTime: Duration? = null

    /**
     * Function which determines the retry delay, as well as if a retry should take place
     */
    var retryHandler: (RetryHandler.() -> Unit) = {
        if (firstConnectionFailed || didError) {
            dontRetry()
        } else {
            retry(retryDelay ?: 5_000)
        }
    }
}

class RetryHandler(
    /**
     * The amount of time that the server has requested we delay the retry for.
     */
    val retryDelay: Long?,
    /**
     * Incremented for each concurrent retry. Will be zero on the first retry.
     */
    val retryCount: Int,
    /**
     * Will be true if this was the first connection, and it failed to establish. The
     * server sent event specification says that no further attempts should be made if the first connection fails.
     */
    val firstConnectionFailed: Boolean,
    val error: SseConnectionError?,
) {
    internal var answered: Boolean = false
    internal var retryMillis: Long = -1
    val didError get() = error != null

    fun retry(milliseconds: Long) {
        answered = true
        retryMillis = milliseconds
    }

    fun retry(delay: Duration) = retry(delay.toMillis())

    fun dontRetry() {
        answered = true
        retryMillis = -1
    }
}

sealed class SseConnectionError : Exception()

/**
 * Used when the server doesn't respond with a sse content type.
 * This indicates that the server may not be serving sse at this url, and should be taken into consideration when retrying.
 */
class SseInvalidContentType(val contentType: ContentType?) : SseConnectionError() {
    override val message: String
        get() = "Received content type of $contentType but expected 'text/event-stream'"
}

/**
 * Used when the server returns a non 200 error code.
 * This might indicate that server is not serving sse at this url. Or for 5xx status codes specifically,
 * it might indicate server capacity problems. This should be taken into consideration when retrying.
 */
class SseServerError(val statusCode: HttpStatusCode) : SseConnectionError() {
    override val message: String
        get() = "Recieved non-ok http status $statusCode whilst attempting to connect to SSE stream"
}