package dev.gounthar.xcpng.toolbox.xo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure halves of the event stream: which frames are changes, and how hard to retry.
 *
 * The transport itself needs a socket and is not tested here. What is tested is everything that
 * decides *meaning*, because that is where being wrong is silent: a frame vocabulary that misses
 * `remove` produces a plugin which simply never notices a deleted VM, and no error anywhere says
 * so.
 */
class XoEventStreamTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun change(name: String?, data: String) = XoChange.from(SseFrame(name, data), json)

    /**
     * The payload is the shape XO actually sends, trimmed to the keys this function reads.
     *
     * Trimmed rather than invented: the real frame carries 45 to 50 keys and the parser is
     * explicitly indifferent to the rest, so pasting all of them would test the `ignoreUnknownKeys`
     * setting instead of the vocabulary.
     */
    private val vmPayload =
        """{"type":"VM","id":"081c58c4-a886-81a0-a401-29828379449e",""" +
            """"uuid":"081c58c4-a886-81a0-a401-29828379449e","name_label":"alpine-test-1",""" +
            """"power_state":"Running","addresses":{}}"""

    @Test
    fun `add, update and remove are all changes`() {
        assertEquals(XoChangeKind.ADDED, change("add", vmPayload)?.kind)
        assertEquals(XoChangeKind.UPDATED, change("update", vmPayload)?.kind)
        assertEquals(XoChangeKind.REMOVED, change("remove", vmPayload)?.kind)
    }

    /**
     * `remove` carries the whole object, not a bare id, and this is the measured fact the parser
     * would otherwise get wrong by reasonable guesswork. A deletion frame observed on 2026-08-21
     * carried 46 keys describing the object as it last was.
     */
    @Test
    fun `a removal names the object it removed`() {
        val removed = change("remove", vmPayload)
        assertEquals("081c58c4-a886-81a0-a401-29828379449e", removed?.id)
        assertEquals(VM_COLLECTION, removed?.collection)
    }

    /** The collection comes off the payload's `type`, because a frame never names its subscription. */
    @Test
    fun `the collection is read from the payload type`() {
        val snapshot = change(
            "add",
            """{"type":"VM-snapshot","id":"1b6620c7-500c-186d-43ab-f52d5205fa07"}""",
        )
        assertEquals("VM-snapshot", snapshot?.collection)
        assertTrue(snapshot?.collection != VM_COLLECTION)
    }

    @Test
    fun `init and ping are not changes`() {
        assertNull(change("init", """{"id":"fc9ff83f-0bef-431c-a6c8-7fd2c7ee5fb6"}"""))
        assertNull(change("ping", """{"ping":1787318767374}"""))
        assertNull(change(null, vmPayload))
    }

    /**
     * A malformed frame must be skipped, never thrown. The stream's entire job is to be
     * long-lived, and an exception here would take a working subscription down and send it round
     * the reconnect loop over one bad payload.
     */
    @Test
    fun `a payload that is not a usable object is skipped rather than thrown`() {
        assertNull(change("update", "not json at all"))
        assertNull(change("update", "[1,2,3]"))
        assertNull(change("update", "{}"))
        assertNull(change("update", """{"type":"VM"}"""))
    }

    /** XO sends both and they agree, but a payload with only `uuid` still identifies an object. */
    @Test
    fun `uuid stands in when id is absent`() {
        assertEquals("only-uuid", change("update", """{"type":"VM","uuid":"only-uuid"}""")?.id)
    }

    @Test
    fun `backoff climbs from one second and caps at thirty`() {
        assertEquals(1_000L, reconnectDelayMillis(1))
        assertEquals(2_000L, reconnectDelayMillis(2))
        assertEquals(4_000L, reconnectDelayMillis(3))
        assertEquals(8_000L, reconnectDelayMillis(4))
        assertEquals(16_000L, reconnectDelayMillis(5))
        assertEquals(30_000L, reconnectDelayMillis(6))
        assertEquals(30_000L, reconnectDelayMillis(7))
    }

    /**
     * The overflow case, which is reachable rather than theoretical: a Toolbox session left open
     * for days against an unreachable pool climbs this counter indefinitely. `1000L shl 62` is
     * negative, and `delay` treats a negative value as "return immediately" rather than as an
     * error, so getting this wrong turns the backoff into the busy loop it exists to prevent.
     */
    @Test
    fun `a large attempt count stays capped and positive`() {
        for (attempt in listOf(60, 63, 64, 1_000, Int.MAX_VALUE)) {
            assertEquals(30_000L, reconnectDelayMillis(attempt), "attempt $attempt")
        }
    }

    /** A zero or negative attempt must not produce a zero delay. */
    @Test
    fun `attempts below one are treated as the first`() {
        assertEquals(1_000L, reconnectDelayMillis(0))
        assertEquals(1_000L, reconnectDelayMillis(-5))
    }

    /**
     * The backoff resets only for a connection that genuinely worked. A server that accepts a
     * subscription and drops the stream immediately would otherwise be retried at one second
     * forever, because "we connected" looks like success.
     */
    @Test
    fun `only a connection that lasted resets the backoff`() {
        assertEquals(1, nextAttempt(previous = 5, connectionLastedMillis = 60_000))
        assertEquals(1, nextAttempt(previous = 5, connectionLastedMillis = 600_000))
        assertEquals(6, nextAttempt(previous = 5, connectionLastedMillis = 59_999))
        assertEquals(2, nextAttempt(previous = 1, connectionLastedMillis = 0))
    }

    /**
     * The end-of-stream rule, run against the real loop rather than against a copy of it.
     *
     * `readFrames` is the production frame loop with the socket reduced to a line source, so these
     * feed it the lines a `BufferedReader` would hand it and end with the null that means EOF. No
     * `init` frame appears in any of them, which is what keeps `subscribe` from being reached and
     * makes this a test rather than an outbound request.
     */
    private fun framesFrom(vararg lines: String): List<XoChange> {
        val stream = XoEventStream("https://xoa.invalid", "token")
        val remaining = lines.iterator()
        val collected = mutableListOf<XoChange>()
        runBlocking {
            stream.readFrames(
                readLine = { if (remaining.hasNext()) remaining.next() else null },
                emit = { collected += it },
            )
        }
        return collected
    }

    /**
     * The bug this replaced: a frame the server sent without its trailing blank line was parsed,
     * buffered, and then dropped when the read hit EOF. `SseParser.complete()` documented itself
     * as existing for exactly this and had no caller, so the affordance was dead and the comment
     * described an intention rather than the code.
     *
     * XO always sends the blank line, so nothing was lost against the appliance directly. A proxy
     * that closes the connection right after a `data:` line is the exposure, and it is not under
     * anybody's control here.
     */
    @Test
    fun `a frame left unterminated at end of stream is emitted rather than dropped`() {
        val changes = framesFrom("event: update", "data: $vmPayload")
        assertEquals(1, changes.size, "the buffered frame should survive EOF")
        assertEquals(XoChangeKind.UPDATED, changes.single().kind)
        assertEquals("081c58c4-a886-81a0-a401-29828379449e", changes.single().id)
    }

    /**
     * The control that makes the test above mean something.
     *
     * A flush that fired unconditionally would emit the last frame twice on a normal stream, which
     * is the obvious way to get the first test passing and a worse bug than the one being fixed:
     * every VM change would be delivered twice, and the plugin would re-read the pool for each.
     */
    @Test
    fun `a normally terminated stream still delivers each frame exactly once`() {
        val changes = framesFrom("event: update", "data: $vmPayload", "")
        assertEquals(1, changes.size, "the trailing blank line already completed this frame")
    }

    @Test
    fun `several frames arrive in order, terminated or not`() {
        val changes = framesFrom(
            "event: add",
            """data: {"type":"VM","id":"a"}""",
            "",
            "event: remove",
            """data: {"type":"VM","id":"b"}""",
            "",
            // Third frame deliberately unterminated: the stream ends here.
            "event: update",
            """data: {"type":"VM","id":"c"}""",
        )
        assertEquals(listOf("a", "b", "c"), changes.map { it.id })
        assertEquals(
            listOf(XoChangeKind.ADDED, XoChangeKind.REMOVED, XoChangeKind.UPDATED),
            changes.map { it.kind },
        )
    }

    /** An empty stream must not manufacture a frame out of an empty parser. */
    @Test
    fun `a stream that closes immediately emits nothing`() {
        assertEquals(0, framesFrom().size)
        assertEquals(0, framesFrom("").size)
        assertEquals(0, framesFrom(": keepalive").size)
    }

    /**
     * A trailing `init` is dropped rather than acted on, and this is the case worth pinning.
     *
     * Subscribing needs a POST against the connection id the frame carries, and at EOF that
     * connection is already gone, so the request would be pointless at best. It falls out of the
     * flush going through [XoChange.from], which answers null for anything that is not an add,
     * update or remove, rather than out of a special case.
     */
    @Test
    fun `a trailing init frame does not become a change and does not subscribe`() {
        val changes = framesFrom("event: init", """data: {"id":"fc9ff83f-0bef-431c-a6c8-7fd2c7ee5fb6"}""")
        assertEquals(0, changes.size)
    }

    /** A ping caught mid-frame by a closing server is still not a change. */
    @Test
    fun `a trailing ping is flushed and then ignored`() {
        assertEquals(0, framesFrom("event: ping", """data: {"ping":1787318767374}""").size)
    }
}
