package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import dev.gounthar.xcpng.toolbox.xo.XoPowerState
import dev.gounthar.xcpng.toolbox.xo.XoVm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one rule that decides whether a connect is refused, and why.
 *
 * This is worth testing because it now has **two** callers that must not disagree:
 * `getConnectionInfo`, which throws, and `getEnvironmentIssueFlow`, which offers a fix. A row that
 * advertises a *Connection settings…* button for a halted VM, or a connect that refuses for a
 * reason the row never mentioned, are both invisible to the compiler and both look like a broken
 * plugin from the outside.
 *
 * What this deliberately does **not** cover: whether Toolbox renders an `EnvironmentIssue` at all.
 * That is not knowable here and no assertion in this file should be read as evidence for it.
 */
class ConnectBlockerTest {

    private class FakeSettings(
        private val backing: MutableMap<String, String> = mutableMapOf(),
    ) : PluginSettingsStore, MutableMap<String, String> by backing

    private class FakeSecrets : PluginSecretStore {
        private val backing = mutableMapOf<String, String>()
        override fun get(key: String): String? = backing[key]
        override fun set(key: String, value: String) { backing[key] = value }
        override fun clear(key: String) { backing.remove(key) }
    }

    private val store = FakeSettings()
    private val settings = XoSettings(store, FakeSecrets())

    private val uuid = "ba93290a-6a69-7663-6310-68ff33fa503f"

    private fun vm(
        power: XoPowerState = XoPowerState.RUNNING,
        address: String? = "192.168.1.173",
    ) = XoVm(uuid = uuid, nameLabel = "tbx-connect-test", powerState = power, mainIpAddress = address)

    private fun blocker(vm: XoVm) = XcpngVmEnvironment.blockerFor(vm, settings)

    // ------------------------------------------------------------ the happy path

    @Test
    fun `running with an address and a default user is not blocked`() {
        settings.defaultSshUser = "root"
        assertNull(blocker(vm()))
    }

    @Test
    fun `a per-VM user is enough on its own`() {
        settings.setSshOverrides(uuid, user = "debian", host = null, port = null)
        assertNull(blocker(vm()))
    }

    @Test
    fun `an override supplies the address the pool could not`() {
        settings.defaultSshUser = "root"
        settings.setSshOverrides(uuid, user = null, host = "192.168.1.99", port = null)
        assertNull(blocker(vm(address = null)))
    }

    // ------------------------------------------------------------ the three refusals

    @Test
    fun `a halted VM is blocked, and settings cannot fix it`() {
        settings.defaultSshUser = "root"
        val blocker = assertNotNull(blocker(vm(power = XoPowerState.HALTED)))
        assertTrue(blocker.message.contains("halted"), blocker.message)
        assertTrue(blocker.message.contains("Start it first"), blocker.message)
        // The button would not start the VM, so offering it would contradict the sentence.
        assertFalse(blocker.fixableInSettings)
    }

    @Test
    fun `power is checked before the address, so an override does not mask a halted VM`() {
        settings.defaultSshUser = "root"
        settings.setSshOverrides(uuid, user = null, host = "192.168.1.99", port = null)
        val blocker = assertNotNull(blocker(vm(power = XoPowerState.HALTED, address = null)))
        assertTrue(blocker.message.contains("Start it first"), blocker.message)
    }

    @Test
    fun `running with no address anywhere is blocked, and settings can fix it`() {
        settings.defaultSshUser = "root"
        val blocker = assertNotNull(blocker(vm(address = null)))
        assertTrue(blocker.message.contains("reports no address"), blocker.message)
        assertTrue(blocker.fixableInSettings)
    }

    @Test
    fun `a missing username is blocked, and settings can fix it`() {
        val blocker = assertNotNull(blocker(vm()))
        assertTrue(blocker.message.contains("no SSH username"), blocker.message)
        assertTrue(blocker.fixableInSettings)
    }

    /**
     * The username message names the address, and it has to name the one that would be used.
     * Getting this wrong tells the user the VM is reachable somewhere it is not.
     */
    @Test
    fun `the username message quotes the override in preference to the pool address`() {
        settings.setSshOverrides(uuid, user = null, host = "192.168.1.99", port = null)
        val blocker = assertNotNull(blocker(vm(address = "192.168.1.173")))
        assertTrue(blocker.message.contains("192.168.1.99"), blocker.message)
        assertFalse(blocker.message.contains("192.168.1.173"), blocker.message)
    }

    // ------------------------------------------------------------ ordering

    /**
     * Address before username. Both are missing here, and the address is the one to say, because
     * a username typed for a VM that will never report an address does not get the user anywhere.
     */
    @Test
    fun `the address is reported before the username when both are missing`() {
        val blocker = assertNotNull(blocker(vm(address = null)))
        assertTrue(blocker.message.contains("reports no address"), blocker.message)
    }

    @Test
    fun `every refusal names the VM`() {
        val cases = listOf(vm(power = XoPowerState.HALTED), vm(address = null), vm())
        cases.forEach { assertTrue(assertNotNull(blocker(it)).message.contains("tbx-connect-test")) }
    }

    @Test
    fun `suspended and paused are refused too, not just halted`() {
        settings.defaultSshUser = "root"
        listOf(XoPowerState.SUSPENDED, XoPowerState.PAUSED).forEach { power ->
            val blocker = assertNotNull(blocker(vm(power = power)), "$power should block")
            assertEquals(false, blocker.fixableInSettings)
            assertTrue(blocker.message.contains(power.name.lowercase()), blocker.message)
        }
    }
}
