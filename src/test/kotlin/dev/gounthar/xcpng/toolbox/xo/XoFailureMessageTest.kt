package dev.gounthar.xcpng.toolbox.xo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `xoFailureMessage` against Xen Orchestra's own refusals.
 *
 * Every body below was captured from the lab appliance on 2026-08-20 with `curl` and is pasted
 * rather than composed. That is the point of the file: the branch this code exists for is the
 * licence gate, and the only thing separating it from an ordinary permission refusal is a nested
 * `featureCode` key that appears in no published document.
 *
 * The assertions deliberately check what a message must **not** say as well as what it must. The
 * defect being fixed was not a missing message — there was one, and it was confident, and it sent
 * the reader to the URL and the token when both were correct.
 */
class XoFailureMessageTest {

    /** `GET /rest/v0/vms?limit=1` with a malformed token, and with none at all. Both give this. */
    private val invalidCredentials = """{ "error": "invalid credentials" }"""

    /** `GET /rest/v0/acl-roles` **as admin** on an unlicensed appliance. */
    private val licenceGate = """
        {
          "error": "feature Unauthorized",
          "data": { "featureCode": "RBAC", "currentPlan": 1, "minPlan": 3 }
        }
    """.trimIndent()

    /** `GET /rest/v0/dashboard` with a non-admin token. No `data` object, and that is the tell. */
    private val notEnoughPermissions = """{ "error": "not enough permissions" }"""

    /** `GET /rest/v0/vmsnope`. Express's own 404 page: HTML, so it does not parse as JSON. */
    private val expressNotFound = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Error</title>
        </head>
        <body>
        <pre>Cannot GET /rest/v0/vmsnope</pre>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `the status and the route are always named`() {
        val message = xoFailureMessage("/vms", 403, licenceGate)
        assertTrue(message.startsWith("Xen Orchestra returned 403 for /vms."), message)
    }

    @Test
    fun `a rejected token says so and clears the URL`() {
        val message = xoFailureMessage("/vms", 401, invalidCredentials)
        assertTrue(message.contains("token was rejected"), message)
        assertTrue(message.contains("the URL is right"), message)
    }

    @Test
    fun `the licence gate names the feature and both plan numbers`() {
        val message = xoFailureMessage("/vms", 403, licenceGate)
        assertTrue(message.contains("licence"), message)
        assertTrue(message.contains("RBAC"), message)
        assertTrue(message.contains("plan 1"), message)
        assertTrue(message.contains("plan 3"), message)
    }

    /**
     * The regression this whole file is for. On the licence gate the URL and the token are the two
     * things that are known good, so a message telling the reader to check them is worse than
     * silence — it costs them a search through the only settings that cannot be at fault.
     */
    @Test
    fun `the licence gate does not send the reader back to the URL or the token`() {
        val message = xoFailureMessage("/vms", 403, licenceGate)
        assertTrue(message.contains("both correct"), message)
        assertFalse(message.contains("Check the URL"), message)
    }

    /**
     * A licence refusal and a permission refusal are both 403 and both carry an `error` string.
     * XO tells them apart and so must this, or the advice is right half the time.
     */
    @Test
    fun `a permission refusal is not reported as a licence problem`() {
        val message = xoFailureMessage("/dashboard", 403, notEnoughPermissions)
        assertTrue(message.contains("privileges"), message)
        assertFalse(message.contains("licence"), message)
    }

    /**
     * Keyed on the missing `featureCode`, not on the words "not enough permissions". XO is free to
     * reword a message; the discriminator is the shape of the body.
     */
    @Test
    fun `a 403 with no error body at all still reads as a permission refusal`() {
        val message = xoFailureMessage("/dashboard", 403, "")
        assertTrue(message.contains("privileges"), message)
    }

    /**
     * And it must not open by claiming Xen Orchestra answered, which is the one thing this branch
     * knows to be false: an unparseable 404 means the request never reached a route, so what is
     * at that address is exactly the open question.
     */
    @Test
    fun `an HTML 404 points at the base URL rather than at the credentials`() {
        val message = xoFailureMessage("/vms", 404, expressNotFound)
        assertTrue(message.contains("never reached a Xen Orchestra route"), message)
        assertTrue(message.contains("/rest/v0"), message)
        assertFalse(message.contains("token"), message)
        assertFalse(message.startsWith("Xen Orchestra returned"), message)
    }

    /**
     * A 404 that XO itself produced is a real answer about a real route, so it must not be turned
     * into advice about the base URL — that URL demonstrably works, since a route answered.
     */
    @Test
    fun `a JSON 404 is XO answering and keeps XO's own words`() {
        val message = xoFailureMessage("/vms/nope", 404, """{ "error": "no such VM nope" }""")
        assertTrue(message.contains("no such VM nope"), message)
        assertFalse(message.contains("base URL"), message)
    }

    @Test
    fun `an unrecognised failure falls back to XO's own error string`() {
        val message = xoFailureMessage(
            "/vms/x/actions/start",
            500,
            """{ "error": "VM_BAD_POWER_STATE(OpaqueRef:1, halted, running)" }""",
        )
        assertTrue(message.contains("VM_BAD_POWER_STATE"), message)
    }

    @Test
    fun `an empty body on an unrecognised status still produces a sentence`() {
        val message = xoFailureMessage("/vms", 502, "")
        assertTrue(message.contains("502"), message)
        assertTrue(message.contains("No detail"), message)
    }
}
