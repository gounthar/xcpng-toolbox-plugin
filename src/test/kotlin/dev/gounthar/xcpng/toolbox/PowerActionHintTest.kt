package dev.gounthar.xcpng.toolbox

import dev.gounthar.xcpng.toolbox.XcpngVmEnvironment.Companion.cleanShutdownHint
import dev.gounthar.xcpng.toolbox.XcpngVmEnvironment.Companion.staleRowHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The hints shown when a power verb fails.
 *
 * Every failure string below is a **verbatim** message built from a body measured against the lab
 * pool on 2026-08-29, not one invented to match the code. That distinction is the whole value of
 * the file: the fixture that agrees with the belief being tested is this repository's most
 * frequent defect, and the previous version of this behaviour survived precisely because nobody
 * had ever run the failing call.
 *
 * The wrapping is `XoRestClient.orThrow`'s: `"$what returned $status: ${xoError()}"`.
 */
class PowerActionHintTest {

    // The three measured bodies, wrapped exactly as orThrow wraps them. Note the argument list is
    // (ref, ...every acceptable state..., actual) and its length differs per verb, which is why
    // nothing under test tries to read the states out of it.
    private val hardShutdownOnHalted =
        "hard_shutdown on ba93290a-6a69-7663-6310-68ff33fa503f returned 500: " +
            "VM_BAD_POWER_STATE(OpaqueRef:27ac60fd-737f-7ad8-dcc5-181d89fa27df, " +
            "paused, suspended, running, halted)"

    private val cleanShutdownOnHalted =
        "clean_shutdown on ba93290a-6a69-7663-6310-68ff33fa503f returned 500: " +
            "VM_BAD_POWER_STATE(OpaqueRef:27ac60fd-737f-7ad8-dcc5-181d89fa27df, running, halted)"

    private val startOnRunning =
        "start on 081c58c4-a886-81a0-a401-29828379449e returned 500: " +
            "VM_BAD_POWER_STATE(OpaqueRef:f099c3d9-7dd5-d95c-fa9d-920a79d7522f, halted, running)"

    @Test
    fun `a force shut down that lost the race is explained rather than shown raw`() {
        val hint = staleRowHint(hardShutdownOnHalted)

        // The regression this file exists for. This button carried no hint at all until its
        // failure path was exercised, so the reader got the XAPI code and nothing else.
        assertNotNull(hint, "force shut down on an already-halted VM must be explained")
        assertEquals(
            "The VM is no longer in the state this list showed, so the pool refused the action and " +
                "nothing was changed.",
            hint,
        )
    }

    @Test
    fun `a clean shut down that lost the race gets the same explanation`() {
        assertEquals(
            "The VM is no longer in the state this list showed, so the pool refused the action and " +
                "nothing was changed.",
            cleanShutdownHint(cleanShutdownOnHalted),
        )
    }

    @Test
    fun `start keeps the hint it already had`() {
        assertNotNull(staleRowHint(startOnRunning))
    }

    @Test
    fun `a guest with no agent is told to force it instead, not that the row is stale`() {
        // The other branch of the clean shutdown button, and the one where the two hints would be
        // actively harmful if swapped: this VM is running, so "it has been re-read" is false, and
        // cutting the power is the actionable answer.
        val detail =
            "clean_shutdown on d1b0eabb-bd80-8b72-0fd1-5f2c1860fd10 returned 500: " +
                "VM_LACKS_FEATURE(OpaqueRef:1a2b3c4d-0000-0000-0000-000000000000)"

        assertEquals(
            "The guest has no XCP-ng agent listening, so it cannot be asked to shut itself " +
                "down. Use \"Force shut down\" instead.",
            cleanShutdownHint(detail),
        )
    }

    @Test
    fun `a failure that is neither gets no hint, so hints stay worth reading`() {
        // A hint under every failure teaches people to ignore hints, which is the reason the
        // parameter is nullable at all. A transport failure carries no XAPI code.
        val detail = "hard_shutdown on ba93290a-6a69-7663-6310-68ff33fa503f returned 500: no detail"

        assertNull(staleRowHint(detail))
        assertNull(cleanShutdownHint(detail))
    }

    @Test
    fun `the code is matched, not the surrounding wording`() {
        // orThrow's wrapper carries the verb, the uuid and the status, none of which the hint may
        // depend on: XO could reword its own text and the code would still be the diagnosis.
        assertNotNull(staleRowHint("VM_BAD_POWER_STATE"))
        assertNotNull(staleRowHint("totally different framing VM_BAD_POWER_STATE trailing words"))
    }
}
