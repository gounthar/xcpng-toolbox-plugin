package dev.gounthar.xcpng.toolbox.xo

import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `xoUnreachableMessage`, which is what the settings page's Test connection button shows when the
 * appliance never answered at all.
 *
 * These are the failures with no status code, so [xoFailureMessage] has nothing to read and the
 * only evidence is the exception type. The JDK's own messages for them are a bare hostname,
 * `Connection refused`, or `PKIX path building failed` — each naming the symptom and none naming
 * anything the reader can act on, while every input that could be at fault is on screen in front
 * of them.
 *
 * Every test asserts on the type, never on the JDK's wording, and that is the point rather than a
 * convenience: a JDK message is not API.
 */
class XoUnreachableMessageTest {

    private val url = "https://xoa.example.com"

    @Test
    fun `an unresolvable host points at the name and not at the token`() {
        val message = xoUnreachableMessage(url, UnknownHostException("xoa.example.com"))
        assertTrue(message.contains("DNS"), message)
        assertTrue(message.contains(url), message)
        assertFalse(message.contains("token"), message)
    }

    /**
     * The branch that earns the whole function. XOA ships a self-signed certificate, so a first
     * connection failing on TLS is the ordinary first experience rather than a fault, and the fix
     * is a checkbox on the same form — so the message names that checkbox by its exact label.
     *
     * It also guards an ordering trap: `SSLHandshakeException` is an `SSLException` is an
     * `IOException`, so a `when` that tested `IOException` first would swallow this and print
     * `PKIX path building failed` instead.
     */
    @Test
    fun `a certificate failure names the checkbox that fixes it`() {
        val message = xoUnreachableMessage(url, SSLHandshakeException("PKIX path building failed"))
        // The constant, not a copy of it. The invariant worth holding is "the message names the
        // checkbox", not "the label is this exact wording" — asserting the literal would turn a
        // legitimate rename into a test failure while doing nothing about the real risk, which is
        // the two strings drifting apart.
        assertTrue(message.contains(SELF_SIGNED_CHECKBOX_LABEL), message)
        assertTrue(message.contains("self-signed"), message)
    }

    @Test
    fun `a refused connection blames the port rather than the hostname`() {
        val message = xoUnreachableMessage(url, ConnectException("Connection refused"))
        assertTrue(message.contains("port"), message)
        assertFalse(message.contains("certificate"), message)
        assertFalse(message.contains("DNS"), message)
    }

    /**
     * Refused and timed out are the two failures a reader is most likely to conflate, and they
     * point at different things: something said no, versus something said nothing.
     */
    @Test
    fun `a timeout is not reported as a refusal`() {
        val timeout = xoUnreachableMessage(url, SocketTimeoutException("connect timed out"))
        val refused = xoUnreachableMessage(url, ConnectException("Connection refused"))
        assertTrue(timeout.contains("did not answer in time"), timeout)
        assertFalse(timeout.contains("Nothing is listening"), timeout)
        assertTrue(refused != timeout)
    }

    @Test
    fun `an unbuildable URL says what the field should hold`() {
        val message = xoUnreachableMessage("wat://x", MalformedURLException("unknown protocol"))
        assertTrue(message.contains("https://xoa.example.com"), message)
    }

    @Test
    fun `an unrecognised transport failure still names the address and carries the cause`() {
        val message = xoUnreachableMessage(url, IOException("stream closed"))
        assertTrue(message.contains(url), message)
        assertTrue(message.contains("stream closed"), message)
    }

    /**
     * The case that must NOT be translated. `ping()` throws `IllegalStateException` carrying
     * whatever [xoFailureMessage] produced — the licence-gate sentence, the rejected-token
     * sentence — and that text is already the best answer available. Wrapping it in a second
     * sentence about connectivity would contradict it, since the appliance demonstrably answered.
     */
    @Test
    fun `a refusal Xen Orchestra actually sent is passed through untouched`() {
        val refusal = xoFailureMessage("/vms", 401, """{ "error": "invalid credentials" }""")
        val message = xoUnreachableMessage(url, IllegalStateException(refusal))
        assertEquals(refusal, message)
        assertFalse(message.contains("Could not reach"), message)
    }

    @Test
    fun `a failure with no message at all still produces something nameable`() {
        val message = xoUnreachableMessage(url, RuntimeException())
        assertEquals("RuntimeException", message)
    }
}
