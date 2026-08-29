package dev.gounthar.xcpng.toolbox.xo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a refused events subscription says in the log.
 *
 * This string matters out of proportion to its size. A refused subscription does not close the
 * connection: it leaves an open, silent stream, so the environment list quietly stops updating and
 * looks exactly like a pool where nothing is happening. The log line is the only thing that can
 * tell the difference, and it used to be a bare number.
 */
class SubscriptionFailureMessageTest {

    /** XO's own words for the three refusals worth telling apart, all of which are a 403. */
    @Test
    fun `XO's explanation reaches the message`() {
        val licence = subscriptionFailureMessage(
            "VM",
            403,
            """{"error":"feature Unauthorized","data":{"featureCode":"RBAC","currentPlan":1,"minPlan":3}}""",
        )
        assertTrue(licence.contains("featureCode"), licence)
        assertTrue(licence.contains("403"), licence)
        assertTrue(licence.contains("VM"), licence)

        val route = subscriptionFailureMessage("VM", 403, """{"error":"not enough permissions"}""")
        assertTrue(route.contains("not enough permissions"), route)

        val obj = subscriptionFailureMessage("VM", 403, """{"error":"not enough privileges"}""")
        assertTrue(obj.contains("not enough privileges"), obj)
    }

    /**
     * The three above are one word apart and mean a licence gate, a route refusal and an object
     * refusal. A message carrying only the status merges all three into "403", which is the state
     * this replaced.
     */
    @Test
    fun `three refusals that share a status do not share a message`() {
        val messages = listOf(
            """{"error":"feature Unauthorized","data":{"featureCode":"RBAC"}}""",
            """{"error":"not enough permissions"}""",
            """{"error":"not enough privileges"}""",
        ).map { subscriptionFailureMessage("VM", 403, it) }
        assertEquals(3, messages.toSet().size, "each refusal must be distinguishable: $messages")
    }

    /** An empty body says so rather than trailing a colon and nothing. */
    @Test
    fun `an empty body reads as no detail`() {
        assertEquals("subscribing to VM returned 500: no detail", subscriptionFailureMessage("VM", 500, ""))
        assertEquals("subscribing to VM returned 500: no detail", subscriptionFailureMessage("VM", 500, "   \n "))
    }

    /**
     * Something in front of the appliance answers with an HTML page rather than XO's JSON, and an
     * unbounded one of those in a log line buries everything after it.
     */
    @Test
    fun `an oversized body is truncated`() {
        val html = "<html><body>" + "x".repeat(5_000) + "</body></html>"
        val message = subscriptionFailureMessage("VM", 502, html)
        assertTrue(message.length < 260, "message was ${message.length} chars")
    }

    /** Newlines in an HTML error page must not turn one log line into forty. */
    @Test
    fun `a multi-line body is flattened to one line`() {
        val message = subscriptionFailureMessage("VM", 502, "<html>\n  <body>\n    nope\n  </body>\n</html>")
        assertTrue(!message.contains("\n"), message)
        assertTrue(message.contains("<html> <body> nope"), message)
    }

    /**
     * The message truncates, and separately the read that feeds it is bounded.
     *
     * These are two different limits and the first does not imply the second, which is what a
     * CodeRabbit finding on #44 pointed out and it was right: the body used to be pulled in whole
     * by readBytes() and then cut to 200 characters, so the allocation was sized by whatever the
     * far end sent, on a path that runs on every reconnect. The read now stops at
     * ERROR_BODY_PREFIX_BYTES. This test covers the message half; the read half is a private
     * constant on the transport and is documented at its declaration.
     */
    @Test
    fun `the message limit is not what bounds the read`() {
        val message = subscriptionFailureMessage("VM", 502, "y".repeat(100_000))
        assertTrue(message.length < 260, "message was ${message.length} chars")
    }

    /** The collection is named, because two subscriptions share one connection. */
    @Test
    fun `the collection that was refused is named`() {
        val message = subscriptionFailureMessage("VM-snapshot", 404, """{"error":"no such collection"}""")
        assertTrue(message.contains("VM-snapshot"), message)
    }
}
