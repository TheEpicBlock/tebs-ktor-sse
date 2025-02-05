package nl.theepicblock.ktorsse

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedWriter
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A simple server, to facilitate testing of the client
 * @see <a href="https://medium.com/@cse.saiful2119/server-sent-event-sse-in-a-nutshell-how-to-implement-sse-using-a-java-client-and-a-simple-http-7ed4eff580c8">How to Implement SSE Using a Java Client and a Simple HTTP Server</a>
 */
class SseTestServer(port: Int = 0) : AutoCloseable {
    private val server: HttpServer
    val port get() = server.address.port
    val url get() = "http://${server.address.asUri()}:$port/sse/"

    private val listeners = ArrayList<Pair<HttpExchange, BufferedWriter>>()
    val listenerCount get() = listeners.size

    init {
        server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/sse/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("charset", "utf-8")
            exchange.responseHeaders.add("access-control-allow-origin", "*")
            exchange.sendResponseHeaders(200, 0)
            listeners.add(Pair(
                exchange,
                exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8
            )))
        }
        server.executor = null
        server.start()
    }

    fun send(data: String) {
        listeners.forEach {
            it.second.write("data: $data\n\n")
            it.second.flush()
        }
    }

    override fun close() {
        listeners.forEach {
            it.first.close()
        }
        server.stop(1)
    }

    fun InetSocketAddress.asUri(): String {
        if (this.address is Inet6Address) {
            return "[${this.hostString}]"
        } else {
            return this.hostString
        }
    }
}