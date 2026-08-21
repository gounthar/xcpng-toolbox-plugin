package dev.gounthar.xcpng.toolbox.xo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `toXoVm` against XO's own JSON.
 *
 * The payloads here are shaped after real responses from the lab XOA (`@xen-orchestra/rest-api`
 * 0.35.0) rather than invented, because the two rules under test are both measurements rather
 * than design decisions: `mainIpAddress` is a last-known value, and `os_version` is the signal
 * that says whether a guest is talking at all.
 *
 * The stale-address rule is the one that matters. On the lab pool 4 of 6 halted VMs served an
 * address and only 1 of 4 running ones did (exactly backwards from intuition), so a client that
 * passed the field straight through would look healthy right up to the moment an IDE dialled a
 * machine that is not there.
 */
class XoVmParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): XoVm = json.parseToJsonElement(raw).jsonObject.toXoVm()

    @Test
    fun `a running VM with a reporting guest keeps its address`() {
        val vm = parse(
            """
            {
              "uuid": "c9dbe58e-dc37-8926-7e1c-b45e70270cd4",
              "name_label": "XOA-selfservice-test-20260814",
              "power_state": "Running",
              "mainIpAddress": "192.168.1.5",
              "os_version": { "name": "Debian 12", "uname": "6.1.0-52-amd64", "distro": "Debian" }
            }
            """,
        )
        assertEquals("c9dbe58e-dc37-8926-7e1c-b45e70270cd4", vm.uuid)
        assertEquals("XOA-selfservice-test-20260814", vm.nameLabel)
        assertEquals(XoPowerState.RUNNING, vm.powerState)
        assertEquals("192.168.1.5", vm.mainIpAddress)
        assertEquals(true, vm.guestIsReporting)
    }

    /**
     * The trap, stated as a test. This payload is a real shape: halted, and still serving the
     * address the VM had when it last ran.
     */
    @Test
    fun `a halted VM's last known address is dropped`() {
        val vm = parse(
            """
            {
              "uuid": "68e58d93-2fa1-9de1-dd62-f4a221fe7f8a",
              "name_label": "jenkins-golden-debian",
              "power_state": "Halted",
              "mainIpAddress": "192.168.1.31",
              "os_version": { "name": "Debian GNU/Linux 12 (bookworm)", "distro": "debian" }
            }
            """,
        )
        assertEquals(XoPowerState.HALTED, vm.powerState)
        assertNull(vm.mainIpAddress, "a halted VM's address is last-known, not live")
    }

    @Test
    fun `a suspended VM's address is dropped too`() {
        val vm = parse("""{ "uuid": "u", "power_state": "Suspended", "mainIpAddress": "192.168.1.31" }""")
        assertEquals(XoPowerState.SUSPENDED, vm.powerState)
        assertNull(vm.mainIpAddress)
    }

    /** Three of four running VMs on the lab pool were in exactly this state. */
    @Test
    fun `a running VM with no guest agent reports neither an address nor a signal`() {
        val vm = parse(
            """
            {
              "uuid": "d1b0eabb-bd80-8b72-0fd1-5f2c1860fd10",
              "name_label": "alpine-test-3",
              "power_state": "Running",
              "os_version": {}
            }
            """,
        )
        assertEquals(XoPowerState.RUNNING, vm.powerState)
        assertNull(vm.mainIpAddress)
        assertEquals(false, vm.guestIsReporting)
    }

    /**
     * `xentools` is declared in XO's schema and was returned on 0 of 10 VMs here, so the fallback
     * is close to dead code, but it is the documented field, so it stays covered.
     */
    @Test
    fun `xentools is the fallback when os_version is absent entirely`() {
        val vm = parse("""{ "uuid": "u", "power_state": "Running", "xentools": "7.20" }""")
        assertEquals(true, vm.guestIsReporting)
    }

    @Test
    fun `neither signal present reads as unknown rather than as not reporting`() {
        val vm = parse("""{ "uuid": "u", "power_state": "Running" }""")
        assertNull(vm.guestIsReporting, "absent is not the same answer as empty")
    }

    @Test
    fun `power states map one for one onto XO's enum`() {
        assertEquals(XoPowerState.RUNNING, parse("""{"uuid":"u","power_state":"Running"}""").powerState)
        assertEquals(XoPowerState.HALTED, parse("""{"uuid":"u","power_state":"Halted"}""").powerState)
        assertEquals(XoPowerState.PAUSED, parse("""{"uuid":"u","power_state":"Paused"}""").powerState)
        assertEquals(XoPowerState.SUSPENDED, parse("""{"uuid":"u","power_state":"Suspended"}""").powerState)
    }

    /** A state XO adds later must not be read as one we know. */
    @Test
    fun `an unrecognised power state is UNKNOWN rather than a guess`() {
        assertEquals(XoPowerState.UNKNOWN, parse("""{"uuid":"u","power_state":"Migrating"}""").powerState)
        assertEquals(XoPowerState.UNKNOWN, parse("""{"uuid":"u"}""").powerState)
    }

    /** Snapshots come back with `id`; VMs with `uuid`. Both have to work. */
    @Test
    fun `id stands in for a missing uuid`() {
        assertEquals("abc", parse("""{"id":"abc","power_state":"Halted"}""").uuid)
    }

    @Test
    fun `an object with no identifier at all is an error rather than a blank VM`() {
        assertFailsWith<IllegalStateException> { parse("""{"name_label":"nameless"}""") }
    }

    @Test
    fun `a missing name gets a placeholder rather than an empty row`() {
        assertEquals("(unnamed)", parse("""{"uuid":"u","power_state":"Halted"}""").nameLabel)
    }

    /** XO sends JSON null for fields it has no value for, and null is not a string. */
    @Test
    fun `explicit nulls are treated as absent`() {
        val vm = parse(
            """
            { "uuid": "u", "name_label": null, "power_state": "Running", "mainIpAddress": null }
            """,
        )
        assertEquals("(unnamed)", vm.nameLabel)
        assertNull(vm.mainIpAddress)
    }

    /** The client asks for a field subset, but `/vms/{id}` ignores `fields` and returns all 48. */
    @Test
    fun `unrequested fields are ignored rather than failing the parse`() {
        val vm = parse(
            """
            {
              "uuid": "u",
              "power_state": "Running",
              "mainIpAddress": "10.0.0.1",
              "pvDriversDetected": true,
              "virtualizationMode": "hvm",
              "CPUs": { "max": 2, "number": 2 },
              "addresses": { "0/ipv4/0": "10.0.0.1" }
            }
            """,
        )
        assertEquals("10.0.0.1", vm.mainIpAddress)
    }

    /**
     * `pvDriversDetected` is deliberately not consulted. All three alpine VMs on the lab pool
     * reported it true while reporting no address and no `os_version`, so "PV drivers installed"
     * and "guest is talking to us" are different questions.
     */
    @Test
    fun `pvDriversDetected does not stand in for a reporting guest`() {
        val vm = parse(
            """
            { "uuid": "u", "power_state": "Running", "pvDriversDetected": true, "os_version": {} }
            """,
        )
        assertEquals(false, vm.guestIsReporting)
        assertTrue(vm.mainIpAddress == null)
    }
}
