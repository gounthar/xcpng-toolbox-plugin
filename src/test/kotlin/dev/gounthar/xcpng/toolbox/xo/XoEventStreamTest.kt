package dev.gounthar.xcpng.toolbox.xo

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
}
