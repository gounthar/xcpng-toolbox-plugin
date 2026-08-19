package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Key handling and the token's storage rule.
 *
 * Both stores are trivial to fake, which is the whole reason this file exists:
 * [PluginSettingsStore] is a `MutableMap<String, String>` and [PluginSecretStore] is three
 * methods. Nothing here needs Toolbox to be running.
 *
 * The case that earns its keep is the keychain one. A token silently left in plaintext looks
 * identical from the outside to a token stored properly — the pool lists either way — so the only
 * cheap way to know is to assert on where it landed.
 */
class XoSettingsTest {

    private class FakeSettings(
        private val backing: MutableMap<String, String> = mutableMapOf(),
    ) : PluginSettingsStore, MutableMap<String, String> by backing

    private class FakeSecrets : PluginSecretStore {
        val backing = mutableMapOf<String, String>()
        override fun get(key: String): String? = backing[key]
        override fun set(key: String, value: String) { backing[key] = value }
        override fun clear(key: String) { backing.remove(key) }
    }

    private val settings = FakeSettings()
    private val secrets = FakeSecrets()
    private val subject = XoSettings(settings, secrets)

    private val vmA = "d1b0eabb-bd80-8b72-0fd1-5f2c1860fd10"
    private val vmB = "081c58c4-a886-81a0-a401-29828379449e"

    // ------------------------------------------------------------------ pool

    @Test
    fun `an unset base url reads as null rather than as empty`() {
        assertNull(subject.baseUrl)
    }

    @Test
    fun `a blank base url reads as null`() {
        settings["baseUrl"] = "   "
        assertNull(subject.baseUrl)
    }

    @Test
    fun `the base url is trimmed on the way in and out`() {
        subject.baseUrl = "  https://192.168.1.5  "
        assertEquals("https://192.168.1.5", settings["baseUrl"])
        assertEquals("https://192.168.1.5", subject.baseUrl)
    }

    /**
     * `toBooleanStrictOrNull` rather than `toBoolean`, so anything that is not exactly `true`
     * fails closed. Certificate verification is not something to disable on a typo.
     */
    @Test
    fun `allowUnauthorized fails closed on anything but a strict true`() {
        assertFalse(subject.allowUnauthorized)
        settings["allowUnauthorized"] = "yes"
        assertFalse(subject.allowUnauthorized)
        settings["allowUnauthorized"] = "TRUE"
        assertFalse(subject.allowUnauthorized)
        settings["allowUnauthorized"] = "true"
        assertTrue(subject.allowUnauthorized)
    }

    @Test
    fun `isConfigured needs both a url and a token`() {
        assertFalse(subject.isConfigured)
        subject.baseUrl = "https://192.168.1.5"
        assertFalse(subject.isConfigured)
        subject.setToken("a-token")
        assertTrue(subject.isConfigured)
    }

    // ----------------------------------------------------------------- token

    @Test
    fun `setToken writes to the keychain and never to the settings file`() {
        subject.setToken("  43-characters-of-token  ")
        assertEquals("43-characters-of-token", secrets.backing["token"])
        assertEquals("", settings["token"])
        assertEquals("43-characters-of-token", subject.token)
    }

    @Test
    fun `the keychain wins over a plaintext copy`() {
        settings["token"] = "seeded-by-hand"
        secrets.backing["token"] = "from-the-keychain"
        assertEquals("from-the-keychain", subject.token)
        assertFalse(subject.tokenIsPlaintext)
    }

    /** The migration path: a token seeded into the file before the settings page existed. */
    @Test
    fun `a hand-seeded token is still readable and is flagged as plaintext`() {
        settings["token"] = "seeded-by-hand"
        assertEquals("seeded-by-hand", subject.token)
        assertTrue(subject.tokenIsPlaintext)
    }

    @Test
    fun `promoting moves a plaintext token into the keychain and blanks the file`() {
        settings["token"] = "seeded-by-hand"
        subject.promoteTokenToKeychain()
        assertEquals("seeded-by-hand", secrets.backing["token"])
        assertEquals("", settings["token"])
        assertFalse(subject.tokenIsPlaintext)
    }

    @Test
    fun `promoting with nothing to promote leaves the keychain untouched`() {
        secrets.backing["token"] = "already-there"
        subject.promoteTokenToKeychain()
        assertEquals("already-there", secrets.backing["token"])
    }

    // ------------------------------------------------------------------- ssh

    @Test
    fun `ssh overrides are namespaced per VM`() {
        subject.setSshOverrides(vmA, user = "root", host = "192.168.1.99", port = 9999)
        subject.setSshOverrides(vmB, user = "debian", host = null, port = null)

        assertEquals("root", subject.sshUserFor(vmA))
        assertEquals("192.168.1.99", subject.sshHostFor(vmA))
        assertEquals(9999, subject.sshPortFor(vmA))

        assertEquals("debian", subject.sshUserFor(vmB))
        assertNull(subject.sshHostFor(vmB))
        assertEquals(XoSettings.DEFAULT_SSH_PORT, subject.sshPortFor(vmB))
    }

    @Test
    fun `an unset port reads as the default`() {
        assertEquals(22, subject.sshPortFor(vmA))
    }

    /**
     * Blank means "not set", so a deliberate 22 is stored as blank rather than as "22". The two
     * are indistinguishable to a reader today and would only come apart if the default changed.
     */
    @Test
    fun `the default port is stored as blank rather than written out`() {
        subject.setSshOverrides(vmA, user = null, host = null, port = 22)
        assertEquals("", settings["vm.$vmA.sshPort"])
        assertEquals(22, subject.sshPortFor(vmA))
    }

    @Test
    fun `a stored port outside the legal range falls back to the default`() {
        settings["vm.$vmA.sshPort"] = "70000"
        assertEquals(22, subject.sshPortFor(vmA))
    }

    @Test
    fun `a stored port that is not a number falls back to the default`() {
        settings["vm.$vmA.sshPort"] = "ssh"
        assertEquals(22, subject.sshPortFor(vmA))
    }

    @Test
    fun `clearing an override removes it rather than storing whitespace`() {
        subject.setSshOverrides(vmA, user = "root", host = "192.168.1.99", port = 9999)
        subject.setSshOverrides(vmA, user = "  ", host = "  ", port = null)
        assertNull(subject.sshUserFor(vmA))
        assertNull(subject.sshHostFor(vmA))
        assertEquals(22, subject.sshPortFor(vmA))
    }

    @Test
    fun `the pool default username is independent of any VM override`() {
        subject.defaultSshUser = "  root  "
        assertEquals("root", subject.defaultSshUser)
        assertNull(subject.sshUserFor(vmA))
    }

    @Test
    fun `clearing the pool default reads as null`() {
        subject.defaultSshUser = "root"
        subject.defaultSshUser = null
        assertNull(subject.defaultSshUser)
    }
}
