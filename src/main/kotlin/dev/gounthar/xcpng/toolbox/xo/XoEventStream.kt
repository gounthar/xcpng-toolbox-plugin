package dev.gounthar.xcpng.toolbox.xo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** What happened to one object, as far as the subscribed user is concerned. */
internal enum class XoChangeKind { ADDED, UPDATED, REMOVED }

/**
 * One object change pushed by Xen Orchestra.
 *
 * [collection] is the object's own `type` field rather than the subscription it arrived on, and
 * that is forced by the wire format rather than chosen: **a frame does not say which subscription
 * produced it.** Two subscriptions on one connection interleave on the same stream and the only
 * thing telling them apart is `type` inside the payload. Measured 2026-08-21 by subscribing to
 * `VM` and `VM-snapshot` at once and watching both arrive undifferentiated.
 */
internal data class XoChange(
    val kind: XoChangeKind,
    val collection: String,
    val id: String,
) {
    companion object {
        /**
         * One object change, or null for a frame that is not one.
         *
         * Where the frame vocabulary is pinned down, measured against the lab appliance
         * 2026-08-21:
         *
         * - `init` carries `{"id": "<connection id>"}` and is the transport's business, not this
         *   one's. **The field is `id`, not `connectionId`.** Reading it wrong yields
         *   `POST /events//subscriptions`, a 404 with an HTML body, and a stream that looks dead
         *   rather than misaddressed. That cost a wasted run before it was written down.
         * - `ping` carries `{"ping": <epoch millis>}` every 30 seconds and is not a change.
         * - `add`, `update` and `remove` **all carry the complete object**, not a delta and not a
         *   bare id. A `remove` frame carried 46 keys describing the object as it last was. Worth
         *   knowing, because the obvious guess (that a removal names only an id) would have made
         *   this return null for every deletion.
         */
        fun from(frame: SseFrame, json: Json): XoChange? {
            val kind = when (frame.name) {
                "add" -> XoChangeKind.ADDED
                "update" -> XoChangeKind.UPDATED
                "remove" -> XoChangeKind.REMOVED
                else -> return null
            }
            // Defensive rather than trusting: a payload that is not an object, or one with no
            // usable identity, is skipped rather than thrown. A malformed frame must not take down
            // a stream whose entire job is to be long-lived.
            val obj = runCatching { json.parseToJsonElement(frame.data) as? JsonObject }.getOrNull()
                ?: return null
            val id = obj.str("id") ?: obj.str("uuid") ?: return null
            return XoChange(kind, obj.str("type") ?: "", id)
        }
    }
}

/** The collection this plugin watches. A VM is an environment; nothing else is. */
internal const val VM_COLLECTION = "VM"

/**
 * How long a connection must survive before it counts as healthy enough to reset the backoff.
 *
 * Without this, a server that accepts a subscription and then immediately drops the stream gets
 * retried at the shortest delay forever, because "we subscribed successfully" looks like success.
 * A minute is long enough that a genuinely working stream always clears it: XO's keepalive is
 * every 30 seconds, so a healthy connection has sent two pings by then.
 */
private const val STABLE_CONNECTION_MILLIS = 60_000L

/**
 * Read timeout, chosen against the measured keepalive rather than picked round.
 *
 * XO pings every **30 seconds** (measured: two frames 30001 ms apart). Three missed pings is a
 * dead connection by any reasonable reading, and a timeout is the only way to notice one: a socket
 * whose peer vanished without a FIN blocks in `read` indefinitely, and nothing else here would
 * ever fire.
 */
private const val READ_TIMEOUT_MILLIS = 95_000

/**
 * The delay before reconnect attempt [attempt], capped.
 *
 * Exponential from one second to thirty, which is the same order as XO's keepalive: reconnecting
 * appreciably faster than the appliance's own heartbeat buys nothing, and a plugin that hammers a
 * pool it cannot reach is exactly the behaviour an operator notices and dislikes.
 *
 * Pure and internal so it can be tested. [attempt] is 1-based; anything lower is treated as 1
 * rather than producing a zero delay, because a zero-delay reconnect loop is a busy wait.
 */
internal fun reconnectDelayMillis(attempt: Int): Long {
    val n = attempt.coerceAtLeast(1)
    // Capped before the shift, not after. `1000L shl 62` is negative, and a negative delay is not
    // an error in `delay`: it returns immediately, turning the backoff into the busy loop it
    // exists to prevent. Over a Toolbox session left open for days that is reachable.
    val capped = n.coerceAtMost(6)
    return (1_000L shl (capped - 1)).coerceAtMost(30_000L)
}

/**
 * The next backoff attempt number, given how long the connection that just died had lasted.
 *
 * Pure so the policy is testable without a socket. See [STABLE_CONNECTION_MILLIS] for why a
 * connection that merely opened is not treated as a success.
 */
internal fun nextAttempt(previous: Int, connectionLastedMillis: Long): Int =
    if (connectionLastedMillis >= STABLE_CONNECTION_MILLIS) 1 else previous + 1

/**
 * Xen Orchestra's server-sent events stream, as a cold [Flow] of object changes.
 *
 * **Why this exists:** without it the provider re-reads the whole pool only when its page becomes
 * visible, so a VM finishing its boot is noticed when the user next happens to look. The stream
 * pushes instead, and a power transition or a guest agent coming up is seen in about a second.
 *
 * **What it deliberately does not do is parse VMs out of the payload.** An event carries the raw
 * XO object, and `mainIpAddress` is computed by the REST representation layer *above* that object.
 * It is simply absent from an event, confirmed by subscribing with `fields: "*"` and reading all
 * 49 keys. The address-shaped field is `addresses`, a map keyed `device/family/index`
 * (`{"0/ipv4/0": "192.168.1.173", "0/ipv6/0": "…"}`). Choosing a primary out of that is a rule XO
 * already owns and that we would be reimplementing from two observed samples, so this class
 * reports *that* something changed and leaves *what it now is* to a REST read. One implementation
 * of the address rule, and it is XO's, which matters because that field is the one this plugin
 * most depends on being right, and a wrong address looks like a working connection until an IDE
 * dials it.
 *
 * The transport is [HttpURLConnection] for the same forced reason as [XoRestClient]: Toolbox ships
 * a jlink'd JRE without `java.net.http`, so the obvious client is a `NoClassDefFoundError` at
 * runtime. A long-lived streaming read on `HttpURLConnection` is fine; the reconnect, the backoff
 * and the cancellation are ours to write, and are below.
 */
internal class XoEventStream(
    baseUrl: String,
    private val token: String,
    private val allowUnauthorized: Boolean = false,
    private val collections: List<String> = listOf(VM_COLLECTION),
) {

    private val base = baseUrl.trimEnd('/') + "/rest/v0"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every change, reconnecting for as long as the collector is alive.
     *
     * Cold: nothing connects until it is collected, and cancelling the collector tears the socket
     * down. That is what ties the connection to the provider's visibility rather than to the
     * plugin's lifetime, which is the leak the issue warned about.
     *
     * Errors are not propagated. A dropped stream is an ordinary event on a long-lived connection
     * (a pool reboots, a laptop suspends, a VPN drops), and a flow completing exceptionally would
     * take the reconnect logic with it. A caller that wants to know passes [onDisconnect], which
     * is called with the cause, or null when the server simply closed the stream.
     */
    fun changes(onDisconnect: (Throwable?) -> Unit = {}): Flow<XoChange> = flow {
        var attempt = 1
        while (currentCoroutineContext().isActive) {
            val startedAt = System.nanoTime()
            var failure: Throwable? = null
            try {
                readOneConnection { emit(it) }
            } catch (cancellation: CancellationException) {
                // Cancellation is the collector going away, not a fault. Rethrowing keeps the
                // flow's completion honest; swallowing it here would turn a cancelled collect into
                // an infinite reconnect loop against a pool nobody is looking at.
                throw cancellation
            } catch (e: Exception) {
                failure = e
            }
            // A deliberate stop must not be reported as a drop, and reaching here does not prove
            // it was one. Cancellation gets in by two routes that both look like a failure: the
            // read loop's own `isActive` check exits normally, so `failure` is null; and the
            // cancellation handler disconnects the socket, so a blocked read throws IOException.
            // Neither is the stream dying: both are the user navigating away from the page.
            //
            // Caught by the live probe on 2026-08-21, which logged `disconnected, cause=null` on a
            // clean shutdown of the collector. Left alone it would have put "event stream dropped,
            // reconnecting" in the log every single time the provider was hidden, which is a lie
            // in the one place someone goes to find out whether the stream is healthy.
            if (!currentCoroutineContext().isActive) break
            onDisconnect(failure)
            val lastedMillis = (System.nanoTime() - startedAt) / 1_000_000
            attempt = nextAttempt(attempt, lastedMillis)
            delay(reconnectDelayMillis(attempt))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One connection, from `init` until the stream dies. Returns normally when the server closed.
     *
     * The `invokeOnCompletion` handle is the load-bearing part and the reason this is not a plain
     * `use {}`. **A blocking `readLine()` does not observe coroutine cancellation**, so a cancelled
     * collector would otherwise sit on an open socket until the read timeout, up to 95 seconds of
     * a connection nobody wants, per hide. Disconnecting from the cancelling thread closes the
     * socket and makes the blocked read throw, which is the only thing that actually interrupts it.
     */
    private suspend fun readOneConnection(emit: suspend (XoChange) -> Unit) {
        val connection = openStream()
        val handle = currentCoroutineContext().job.invokeOnCompletion { connection.disconnect() }
        try {
            val parser = SseParser()
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            var subscribed = false
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                val frame = parser.accept(line) ?: continue
                if (!subscribed && frame.name == "init") {
                    // The subscription is a separate request against the connection id this frame
                    // carries, and it has to happen before anything is delivered: a bare
                    // connection is silent by design. Frames are not missed while it runs; they
                    // queue in the socket buffer.
                    subscribe(connectionIdOf(frame) ?: break)
                    subscribed = true
                    continue
                }
                XoChange.from(frame, json)?.let { emit(it) }
            }
        } finally {
            handle.dispose()
            connection.disconnect()
        }
    }

    /** The connection id out of an `init` frame. `id`, not `connectionId`; see [XoChange.from]. */
    private fun connectionIdOf(frame: SseFrame): String? =
        runCatching { (json.parseToJsonElement(frame.data) as? JsonObject)?.str("id") }.getOrNull()

    /**
     * Ask for one collection per subscription.
     *
     * `fields` is omitted deliberately. This class reads only `type` and `id` off a payload and
     * re-reads the object over REST for anything else, so naming fields would trade a smaller
     * frame for a list that has to stay in step with a parser which does not use it.
     */
    private fun subscribe(connectionId: String) {
        for (collection in collections) {
            val response = post(
                "/events/$connectionId/subscriptions",
                """{"collection":"$collection"}""",
            )
            // A refused subscription leaves a connected but silent stream, which is the single
            // most confusing failure this class can have: it is indistinguishable from a quiet
            // pool. Throwing sends it round the reconnect loop with the reason in the log.
            require(response in 200..299) { "subscribing to $collection returned $response" }
        }
    }

    private fun openStream(): HttpURLConnection =
        (URL("$base/events").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Cookie", "authenticationToken=$token")
            setRequestProperty("Accept", "text/event-stream")
            // A buffered stream is a stream that delivers nothing until it has enough to be worth
            // delivering, which for a keepalive-paced feed can be minutes.
            setRequestProperty("Cache-Control", "no-cache")
            applyTlsPolicy()
        }

    private fun post(path: String, body: String): Int {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Cookie", "authenticationToken=$token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            applyTlsPolicy()
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** XOA ships a self-signed certificate, so this is the common case rather than an edge one. */
    private fun HttpURLConnection.applyTlsPolicy() {
        if (allowUnauthorized && this is HttpsURLConnection) {
            sslSocketFactory = TrustEverythingSslContext.create().socketFactory
            setHostnameVerifier { _, _ -> true }
        }
    }
}
