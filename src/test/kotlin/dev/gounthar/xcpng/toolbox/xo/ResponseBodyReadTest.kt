package dev.gounthar.xcpng.toolbox.xo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one rule `readResponseBody` carries: a failure body is bounded, a success body is not.
 *
 * Worth a test rather than a comment because the two halves fail in opposite ways. Getting the
 * failure branch wrong costs an allocation nobody sees, and getting the success branch wrong
 * truncates the pool's VM list, which the client then fails to parse while the appliance sent a
 * perfectly good response. So the success case is asserted with a payload deliberately larger than
 * the bound, which is the shape the wrong fix would have broken.
 */
class ResponseBodyReadTest {

    private fun streamOf(text: String): InputStream =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    /** A VM list the size of a real pool's, and past the bound, so truncating it would show. */
    private fun vmListJson(count: Int): String =
        (0 until count).joinToString(",", "[", "]") { i ->
            """{"uuid":"0000-$i","name_label":"vm-$i","power_state":"Halted",""" +
                """"os_version":{},"padding":"${"x".repeat(200)}"}"""
        }

    @Test
    fun `a failure body larger than the bound is cut to the bound`() {
        val body = readResponseBody(streamOf("y".repeat(ERROR_BODY_PREFIX_BYTES * 3)), bounded = true)
        assertEquals(ERROR_BODY_PREFIX_BYTES, body.length)
    }

    /** Nothing is lost when the body is small, which is every real XO error object. */
    @Test
    fun `a failure body inside the bound arrives whole`() {
        val error = """{"error":"feature Unauthorized","data":{"featureCode":"RBAC"}}"""
        assertEquals(error, readResponseBody(streamOf(error), bounded = false))
        assertEquals(error, readResponseBody(streamOf(error), bounded = true))
    }

    /**
     * The case the shared read would have broken. A pool of 40 VMs is already past 8 KiB, and this
     * plugin's own lab pool holds 11, so the bound is not a theoretical limit for a busy pool.
     */
    @Test
    fun `a success body larger than the bound is read in full and still parses`() {
        val payload = vmListJson(40)
        assertTrue(payload.length > ERROR_BODY_PREFIX_BYTES, "fixture must exceed the bound")

        val body = readResponseBody(streamOf(payload), bounded = false)

        assertEquals(payload, body)
        val vms = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .jsonArray
            .map { it.jsonObject.toXoVm() }
        assertEquals(40, vms.size)
        assertEquals("vm-39", vms.last().nameLabel)
    }

    /**
     * `HttpURLConnection.getErrorStream()` is null when the far end sent no body at all, and a
     * refusal with an empty body is a normal answer rather than a fault, so it must not throw.
     */
    @Test
    fun `a missing stream is an empty body rather than a failure`() {
        assertEquals("", readResponseBody(null, bounded = true))
        assertEquals("", readResponseBody(null, bounded = false))
    }
}
