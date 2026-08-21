package dev.gounthar.xcpng.toolbox.xo

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which verb powers a VM on from each state.
 *
 * This exists because getting it wrong is silent in the worst way: XO answers a `start` on a
 * suspended VM with a 500 rather than a no-op, and the user reads that as a broken plugin. The
 * mapping lives in one place precisely so it can be checked in one place.
 *
 * **It therefore has to check the mapping, and an earlier version of this file did not.** It
 * asserted only that each state had *a* verb, that Running had none, and that the three verbs were
 * distinct, all of which stay true when `start` and `resume` are swapped. Verified by doing
 * exactly that swap and watching the suite pass. So the verb is now *invoked* against a client
 * that records which method it was, which is the only form of this test that can fail for the
 * right reason.
 */
class XoPowerStateTest {

    /** Records the call rather than making one. Every member is unused except the three verbs. */
    private class RecordingClient : XoClient {
        val calls = mutableListOf<String>()
        override fun ping() {}
        override suspend fun listVms(): List<XoVm> = emptyList()
        override suspend fun getVm(uuid: String): XoVm? = null
        override suspend fun start(vm: XoVm) { calls += "start" }
        override suspend fun resume(vm: XoVm) { calls += "resume" }
        override suspend fun unpause(vm: XoVm) { calls += "unpause" }
        override suspend fun cleanShutdown(vm: XoVm) { calls += "cleanShutdown" }
        override suspend fun hardShutdown(vm: XoVm) { calls += "hardShutdown" }
        override suspend fun snapshot(vm: XoVm, nameLabel: String): String? = null
        override suspend fun listSnapshots(vm: XoVm): List<XoSnapshot> = emptyList()
        override suspend fun revertSnapshot(vm: XoVm, snapshotId: String) {}
        override suspend fun primaryIpAddress(vm: XoVm): String? = null
        override fun close() {}
    }

    private val vm = XoVm("u", "a-vm", XoPowerState.HALTED)

    /** The XO endpoint a state's verb actually calls, or null when it has none. */
    private fun verbFor(state: XoPowerState): String? {
        val verb = state.resumeVerb ?: return null
        val client = RecordingClient()
        runBlocking { verb(client, vm) }
        return client.calls.single()
    }

    @Test
    fun `a halted VM is started`() {
        assertEquals("start", verbFor(XoPowerState.HALTED))
    }

    /** `start` on a suspended VM is a 500, not a no-op. This is the swap that must not happen. */
    @Test
    fun `a suspended VM is resumed, not started`() {
        assertEquals("resume", verbFor(XoPowerState.SUSPENDED))
    }

    @Test
    fun `a paused VM is unpaused, not started`() {
        assertEquals("unpause", verbFor(XoPowerState.PAUSED))
    }

    @Test
    fun `a running VM has no verb, so no action is offered`() {
        assertNull(XoPowerState.RUNNING.resumeVerb)
    }

    /** Unknown means XO said something this build does not recognise. Guessing would be a 500. */
    @Test
    fun `an unknown state has no verb rather than a default`() {
        assertNull(XoPowerState.UNKNOWN.resumeVerb)
    }

    @Test
    fun `every state maps to a distinct endpoint`() {
        val verbs = listOf(XoPowerState.HALTED, XoPowerState.SUSPENDED, XoPowerState.PAUSED)
            .map { verbFor(it) }
        assertEquals(verbs.toSet().size, verbs.size, "two states share a verb: $verbs")
    }
}
