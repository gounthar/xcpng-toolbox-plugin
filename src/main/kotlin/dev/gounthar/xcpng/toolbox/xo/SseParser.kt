package dev.gounthar.xcpng.toolbox.xo

/**
 * One server-sent event: the `event:` name and the accumulated `data:` payload.
 *
 * [name] is nullable because the wire format allows a frame with only `data:` lines, which means
 * the default event type `message`. Xen Orchestra always names its events, so in practice this is
 * never null against XO — it is nullable to match the format rather than the appliance.
 */
internal data class SseFrame(val name: String?, val data: String)

/**
 * An incremental parser for `text/event-stream`, fed one line at a time.
 *
 * Separate from [XoEventStream] and pure on purpose: the transport cannot be unit tested without
 * a server, and this can. Everything that decides *what a frame is* lives here, so the transport
 * is left with connecting, reading and reconnecting.
 *
 * Measured against the lab appliance 2026-08-21, and two details are worth stating because they
 * are the kind that get assumed:
 *
 * - **XO terminates lines with a bare `\n`.** No `\r`. Checked with `cat -A` rather than by eye,
 *   because a stray `\r` left on the end of a `data:` payload makes the JSON parse fail with a
 *   message that points at the JSON rather than at the line splitting.
 * - **A frame ends at a blank line**, and XO sends exactly one blank line between frames.
 *
 * The rest of the format is implemented from the specification rather than from the appliance:
 * comment lines, multi-line `data:`, and the `id:`/`retry:` fields XO does not currently send.
 * That is deliberate — they cost three lines each and they are what a client breaks on when the
 * server starts sending them, which is not an event anybody gets told about.
 */
internal class SseParser {

    private var name: String? = null
    private val data = StringBuilder()

    /**
     * Feed one line. Returns a frame when that line completed one, null otherwise.
     *
     * A blank line completes a frame — but only if something was accumulated. Two blank lines in
     * a row must not produce an empty frame, and a keepalive comment followed by a blank line
     * must not either.
     */
    fun accept(line: String): SseFrame? {
        // The spec's line terminators are \n, \r\n and \r. A caller splitting on \n alone leaves
        // the \r here, so it is stripped here rather than trusted to have been handled upstream.
        val text = line.removeSuffix("\r")

        if (text.isEmpty()) return complete()

        // A line starting with a colon is a comment. Some servers use them as keepalives; XO uses
        // a named `ping` event instead, so this is here for the format rather than for XO.
        if (text.startsWith(":")) return null

        val colon = text.indexOf(':')
        val field: String
        val rawValue: String
        if (colon < 0) {
            // A field name with no colon at all is legal and carries an empty value.
            field = text
            rawValue = ""
        } else {
            field = text.substring(0, colon)
            rawValue = text.substring(colon + 1)
        }
        // Exactly one leading space is part of the framing, not of the value. Only one: a payload
        // that genuinely begins with two spaces keeps the second.
        val value = rawValue.removePrefix(" ")

        when (field) {
            "event" -> name = value
            // Multi-line data is joined with newlines, per the format. XO sends single-line JSON,
            // so this branch is untested against the appliance and is here to avoid silently
            // truncating a payload if that ever changes.
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // `id` and `retry` are read and discarded. Discarding them is a decision rather than
            // an omission: this client reconnects with its own backoff (see reconnectDelayMillis)
            // and does not resume from a last-event id, because XO's subscriptions are bound to a
            // connection id that a reconnect replaces anyway.
            "id", "retry" -> Unit
            else -> Unit
        }
        return null
    }

    /**
     * Emit whatever has been accumulated, if anything, and reset.
     *
     * Public so a caller that reaches end-of-stream can flush a frame the server sent without a
     * trailing blank line. XO always sends one; a proxy in the middle might not.
     */
    fun complete(): SseFrame? {
        if (name == null && data.isEmpty()) return null
        val frame = SseFrame(name, data.toString())
        name = null
        data.setLength(0)
        return frame
    }
}
