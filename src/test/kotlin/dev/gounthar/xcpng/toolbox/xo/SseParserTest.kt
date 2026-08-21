package dev.gounthar.xcpng.toolbox.xo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `text/event-stream` parser, which is the only part of the event transport that can be
 * tested without a server.
 *
 * The frames below are **captured from the lab appliance**, not invented. That matters more than
 * usual here: the whole class of bug this guards against is a parser written from the
 * specification against a server that does something slightly different, and a fixture somebody
 * made up shares exactly the assumptions the parser does.
 */
class SseParserTest {

    /** Feed a whole stream, split the way a `BufferedReader` would, and collect what emerges. */
    private fun parse(stream: String): List<SseFrame> {
        val parser = SseParser()
        val frames = stream.split("\n").mapNotNull { parser.accept(it) }
        return frames + listOfNotNull(parser.complete())
    }

    @Test
    fun `the init frame carries id, not connectionId`() {
        // Verbatim off the wire, 2026-08-21. The field name is the whole point of this test:
        // reading it as connectionId yields POST /events//subscriptions and a 404, which reads
        // as a dead stream rather than a misaddressed one.
        val frames = parse("event: init\ndata: {\"id\":\"fc9ff83f-0bef-431c-a6c8-7fd2c7ee5fb6\"}\n\n")
        assertEquals(1, frames.size)
        assertEquals("init", frames[0].name)
        assertEquals("{\"id\":\"fc9ff83f-0bef-431c-a6c8-7fd2c7ee5fb6\"}", frames[0].data)
    }

    @Test
    fun `consecutive frames are separated by one blank line`() {
        val frames = parse(
            "event: ping\ndata: {\"ping\":1787318767374}\n\n" +
                "event: update\ndata: {\"id\":\"a\"}\n\n" +
                "event: ping\ndata: {\"ping\":1787318797375}\n\n",
        )
        assertEquals(listOf("ping", "update", "ping"), frames.map { it.name })
    }

    /**
     * XO uses bare `\n`, checked with `cat -A` rather than by eye. A caller splitting a `\r\n`
     * stream on `\n` alone leaves the `\r` on the payload, and the failure then surfaces as a JSON
     * parse error pointing at the JSON rather than at the line splitting.
     */
    @Test
    fun `a trailing carriage return is not part of the value`() {
        val frames = parse("event: update\r\ndata: {\"id\":\"a\"}\r\n\r\n")
        assertEquals("update", frames.single().name)
        assertEquals("{\"id\":\"a\"}", frames.single().data)
    }

    @Test
    fun `exactly one leading space is framing, and a second is data`() {
        assertEquals("{\"a\":1}", parse("data: {\"a\":1}\n\n").single().data)
        assertEquals(" indented", parse("data:  indented\n\n").single().data)
        assertEquals("{\"a\":1}", parse("data:{\"a\":1}\n\n").single().data)
    }

    @Test
    fun `multi-line data is joined with newlines`() {
        assertEquals("line one\nline two", parse("data: line one\ndata: line two\n\n").single().data)
    }

    /**
     * Blank lines and comments must not manufacture frames. A stream that opens with a keepalive
     * comment and idles would otherwise deliver a run of empty frames, each of which reaches
     * [XoChange.from] and has to be rejected there instead.
     */
    @Test
    fun `blank lines and comments produce no frame`() {
        assertEquals(0, parse("\n\n\n").size)
        assertEquals(0, parse(": keepalive\n\n").size)
        assertEquals(0, parse("").size)
    }

    @Test
    fun `a frame with data but no event name keeps a null name`() {
        val frame = parse("data: {\"id\":\"a\"}\n\n").single()
        assertNull(frame.name)
        assertEquals("{\"id\":\"a\"}", frame.data)
    }

    /** `id:` and `retry:` are read and discarded; they must not leak into the payload. */
    @Test
    fun `id and retry fields are ignored without disturbing the frame`() {
        val frame = parse("id: 7\nretry: 5000\nevent: update\ndata: {\"id\":\"a\"}\n\n").single()
        assertEquals("update", frame.name)
        assertEquals("{\"id\":\"a\"}", frame.data)
    }

    /**
     * A server that closes without a trailing blank line still has one frame's worth of data
     * buffered. XO always sends the blank line; a proxy in between is under nobody's control.
     */
    @Test
    fun `complete flushes a frame the server did not terminate`() {
        val parser = SseParser()
        assertNull(parser.accept("event: update"))
        assertNull(parser.accept("data: {\"id\":\"a\"}"))
        assertEquals(SseFrame("update", "{\"id\":\"a\"}"), parser.complete())
        // And a second call must not re-emit it.
        assertNull(parser.complete())
    }

    /** State must not survive a frame, or an unnamed frame inherits the previous frame's name. */
    @Test
    fun `an event name does not leak into the next frame`() {
        val frames = parse("event: update\ndata: {\"id\":\"a\"}\n\ndata: {\"id\":\"b\"}\n\n")
        assertEquals(listOf("update", null), frames.map { it.name })
        assertEquals(listOf("{\"id\":\"a\"}", "{\"id\":\"b\"}"), frames.map { it.data })
    }
}
