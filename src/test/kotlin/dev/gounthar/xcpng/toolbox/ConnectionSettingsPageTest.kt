package dev.gounthar.xcpng.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The address field is the one place in this plugin where a user types free text, and what they
 * type is usually a working SSH argument rather than a bare address. These cases are the forms
 * that have to survive it.
 *
 * Worth being honest about what this does and does not buy. None of the five bugs found by
 * clicking through Toolbox on 2026-08-19 would have been caught here, because each one was an
 * assumption about Toolbox rather than about a string. This covers the part that is genuinely
 * ours.
 */
class ConnectionSettingsPageTest {

    private fun normalise(user: String = "", host: String = "", port: String = "") =
        ConnectionSettingsPage.normalise(user, host, port)

    @Test
    fun `leaves a plain address alone`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "192.168.1.42", ""),
            normalise(host = "192.168.1.42"),
        )
    }

    @Test
    fun `splits user@host into its two fields`() {
        assertEquals(
            ConnectionSettingsPage.Fields("root", "192.168.1.42", ""),
            normalise(host = "root@192.168.1.42"),
        )
    }

    @Test
    fun `splits host colon port into its two fields`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "192.168.1.42", "2222"),
            normalise(host = "192.168.1.42:2222"),
        )
    }

    @Test
    fun `splits a full user@host colon port`() {
        assertEquals(
            ConnectionSettingsPage.Fields("debian", "vm.local", "2222"),
            normalise(host = "debian@vm.local:2222"),
        )
    }

    /** A username already typed in its own field is the explicit one, so it wins. */
    @Test
    fun `an explicit username survives a conflicting prefix`() {
        assertEquals(
            ConnectionSettingsPage.Fields("debian", "vm.local", ""),
            normalise(user = "debian", host = "root@vm.local"),
        )
    }

    /** The prefix still comes off the address, because it can never be part of one. */
    @Test
    fun `the prefix is stripped even when it is discarded`() {
        assertEquals("vm.local", normalise(user = "debian", host = "root@vm.local").host)
    }

    @Test
    fun `an explicit port survives a conflicting suffix`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "vm.local", "2200"),
            normalise(host = "vm.local:2222", port = "2200"),
        )
    }

    /**
     * Bracketed IPv6 keeps its colons. The single-colon test is what separates `host:port` from
     * an address, so a bracketed literal has to be excluded explicitly or `[::1]` would be
     * mangled into a host of `[` and a port of nothing.
     */
    @Test
    fun `bracketed IPv6 keeps its colons`() {
        assertEquals("[2a01:e0a:96c:c250::1]", normalise(host = "[2a01:e0a:96c:c250::1]").host)
    }

    @Test
    fun `an unbracketed IPv6 address is left alone rather than guessed at`() {
        // More than one colon, so the host:port rule does not fire.
        assertEquals("2a01:e0a:96c:c250::1", normalise(host = "2a01:e0a:96c:c250::1").host)
    }

    @Test
    fun `a port that is not a number is dropped rather than stored`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "vm.local", ""),
            normalise(host = "vm.local", port = "ssh"),
        )
    }

    @Test
    fun `a port outside the legal range is dropped`() {
        assertEquals("", normalise(host = "vm.local", port = "70000").port)
        assertEquals("", normalise(host = "vm.local", port = "0").port)
    }

    /** An out-of-range suffix is not a port, so it stays part of the host rather than vanishing. */
    @Test
    fun `an out of range suffix is left on the address`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "vm.local:70000", ""),
            normalise(host = "vm.local:70000"),
        )
    }

    /**
     * A fumbled paste. Anything after whitespace is not part of a hostname, and storing it would
     * produce a string SSH chokes on.
     */
    @Test
    fun `takes the first token of a pasted command`() {
        assertEquals(
            ConnectionSettingsPage.Fields("root", "192.168.1.42", ""),
            normalise(host = "root@192.168.1.42 -i ~/.ssh/id_ed25519"),
        )
    }

    @Test
    fun `surrounding whitespace is removed`() {
        assertEquals("vm.local", normalise(host = "   vm.local   ").host)
    }

    @Test
    fun `an empty address stays empty`() {
        assertEquals(ConnectionSettingsPage.Fields("", "", ""), normalise())
    }

    /**
     * Documents a real edge rather than endorsing it: a leading `@` leaves nothing before the
     * delimiter, so there is no username to lift and the string is left as typed. Storing `@host`
     * is wrong, but it is visibly wrong on the field, which is the best this page can do — it has
     * no way to report a refusal.
     */
    @Test
    fun `a leading at sign is left visible rather than silently repaired`() {
        assertEquals(
            ConnectionSettingsPage.Fields("", "@vm.local", ""),
            normalise(host = "@vm.local"),
        )
    }
}
