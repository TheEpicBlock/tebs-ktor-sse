package nl.theepicblock.ktorsse

import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.sse.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

suspend fun createSseListener(client: HttpClient, urlString: String, builder: SseListenerBuilder.() -> Unit): SseListener {
    val builderData = SseListenerBuilder()
    builder(builderData)

    val session = if (builderData.requestBuilder == null) {
        client.sseSession(urlString)
    } else {
        client.sseSession(urlString, block = builderData.requestBuilder!!)
    }
    val lJob = session.launch {
        session.incoming.collect(builderData.listener)
    }

    return SseListener(session, lJob)
}

class SseListener(private val session: ClientSSESession, private val listener: Job) : Closeable {
    override fun close() {
        session.cancel()
        listener.cancel()
    }
}

class SseListenerBuilder {
    var requestBuilder: (HttpRequestBuilder.() -> Unit)? = null
    var listener: ((ServerSentEvent) -> Unit) = {}
}