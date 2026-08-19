package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore

/**
 * Where the pool URL, the token and the per-VM connection details come from.
 *
 * [PluginSettingsStore] is a plain `MutableMap<String, String>` that Toolbox persists to
 * `settings.json` in the plugin's data directory, which is
 * `<ToolboxDataLocation>/plugins/<extensionId>/` (note: the *data* directory, not the
 * `cache/plugins` one the code is loaded from). [PluginSecretStore] is backed by the OS keychain,
 * `windows-dpapi` on this machine.
 *
 * Everything here is read at call time rather than cached, so a value edited in the settings page
 * takes effect on the next action without Toolbox being restarted.
 */
class XoSettings(
    private val settings: PluginSettingsStore,
    private val secrets: PluginSecretStore,
) {
    var baseUrl: String?
        get() = settings[KEY_URL]?.trim()?.takeIf { it.isNotBlank() }
        set(value) { settings[KEY_URL] = value?.trim().orEmpty() }

    /** XOA ships a self-signed certificate, so this is usually true on a lab pool. */
    var allowUnauthorized: Boolean
        get() = settings[KEY_INSECURE]?.toBooleanStrictOrNull() ?: false
        set(value) { settings[KEY_INSECURE] = value.toString() }

    /**
     * The token, keychain first.
     *
     * The settings-store fallback is a **development shortcut and a real weakness**: the settings
     * store is plaintext JSON on disk, so a token put there is readable by anything that can read
     * the file. It is kept only to migrate a token seeded by hand before the settings page
     * existed — [promoteTokenToKeychain] moves it and blanks the plaintext copy, and [token]
     * prefers the keychain, so a token written through the UI never lands in the file at all.
     */
    val token: String?
        get() = secrets.get(KEY_TOKEN)?.takeIf { it.isNotBlank() }
            ?: settings[KEY_TOKEN]?.takeIf { it.isNotBlank() }

    /** True when the token came from the plaintext fallback rather than the keychain. */
    val tokenIsPlaintext: Boolean
        get() = secrets.get(KEY_TOKEN).isNullOrBlank() && !settings[KEY_TOKEN].isNullOrBlank()

    /** Writes straight to the keychain, and clears any plaintext copy left over from seeding. */
    fun setToken(value: String) {
        secrets.set(KEY_TOKEN, value.trim())
        settings[KEY_TOKEN] = ""
    }

    fun promoteTokenToKeychain() {
        val plain = settings[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return
        secrets.set(KEY_TOKEN, plain)
        settings[KEY_TOKEN] = ""
    }

    val isConfigured: Boolean get() = baseUrl != null && token != null

    // ------------------------------------------------------------------ ssh

    /**
     * The login name used for any VM that has not been given one of its own.
     *
     * A pool is usually built from one or two templates, so a per-VM username is the exception
     * rather than the rule. Without this default, connecting to a ten-VM pool would mean filling
     * in the same name ten times.
     */
    var defaultSshUser: String?
        get() = settings[KEY_DEFAULT_USER]?.trim()?.takeIf { it.isNotBlank() }
        set(value) { settings[KEY_DEFAULT_USER] = value?.trim().orEmpty() }

    fun sshUserFor(vmUuid: String): String? =
        settings[key(vmUuid, SUFFIX_USER)]?.trim()?.takeIf { it.isNotBlank() }

    /**
     * An address typed by the user, which wins over whatever the pool reports.
     *
     * This is the escape hatch for the open design problem in the project notes: `mainIpAddress` is only
     * trustworthy while a VM is running, and on the lab pool only 1 of 4 running VMs reported one
     * at all. A VM whose guest never talks to the host is otherwise unreachable forever, however
     * well the rest of the plugin works.
     */
    fun sshHostFor(vmUuid: String): String? =
        settings[key(vmUuid, SUFFIX_HOST)]?.trim()?.takeIf { it.isNotBlank() }

    fun sshPortFor(vmUuid: String): Int =
        settings[key(vmUuid, SUFFIX_PORT)]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: DEFAULT_SSH_PORT

    fun setSshOverrides(vmUuid: String, user: String?, host: String?, port: Int?) {
        settings[key(vmUuid, SUFFIX_USER)] = user?.trim().orEmpty()
        settings[key(vmUuid, SUFFIX_HOST)] = host?.trim().orEmpty()
        // Storing the default explicitly would be indistinguishable from a deliberate 22, which
        // matters only if the default ever changes. Blank means "not set".
        settings[key(vmUuid, SUFFIX_PORT)] =
            port?.takeIf { it != DEFAULT_SSH_PORT }?.toString().orEmpty()
    }

    private fun key(vmUuid: String, suffix: String) = "$PREFIX_VM$vmUuid.$suffix"

    companion object {
        const val DEFAULT_SSH_PORT = 22

        private const val KEY_URL = "baseUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_INSECURE = "allowUnauthorized"
        private const val KEY_DEFAULT_USER = "defaultSshUser"
        private const val PREFIX_VM = "vm."
        private const val SUFFIX_USER = "sshUser"
        private const val SUFFIX_HOST = "sshHost"
        private const val SUFFIX_PORT = "sshPort"
    }
}
