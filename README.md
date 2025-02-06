# TEB's Server Sent Events for Ktor
This is an implementation of a [server sent events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
client for [Ktor](https://ktor.io/). Ktor can natively handle SSE events, but I wanted a solution that has easy callbacks
and which natively supports reconnecting when the connection is lost.

This library is pushed to `https://maven.theepicblock.nl` as `nl.theepicblock:tebs-ktor-sse:<version>`