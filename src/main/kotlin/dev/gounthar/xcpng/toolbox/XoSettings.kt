package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore

/**
 * Where the pool URL and token come from.
 *
 * [PluginSettingsStore] is a plain `MutableMap<String, String>` that Toolbox persists to
 * `settings.json` in the plugin's data directory, which is
 * `<ToolboxDataLocation>/plugins/<extensionId>/` (note: the *data* directory, not the
 * `cache/plugins` one the code is loaded from). [PluginSecretStore] is backed by the OS keychain,
 * `windows-dpapi` on this machine.
 */
class XoSettings(
    private val settings: PluginSettingsStore,
    private val secrets: PluginSecretStore,
) {
    var baseUrl: String?
        get() = settings[KEY_URL]?.takeIf { it.isNotBlank() }
        set(value) { settings[KEY_URL] = value.orEmpty() }

    /** XOA ships a self-signed certificate, so this is usually true on a lab pool. */
    var allowUnauthorized: Boolean
        get() = settings[KEY_INSECURE]?.toBooleanStrictOrNull() ?: false
        set(value) { settings[KEY_INSECURE] = value.toString() }

    /**
     * The token, keychain first.
     *
     * The settings-store fallback is a **development shortcut and a real weakness**: the settings
     * store is plaintext JSON on disk, so a token put there is readable by anything that can read
     * the file. It exists because there is no settings UI yet and the keychain cannot be seeded
     * from outside Toolbox. Once a UI writes to [PluginSecretStore], delete the fallback rather
     * than leaving it as a convenience.
     */
    val token: String?
        get() = secrets.get(KEY_TOKEN)?.takeIf { it.isNotBlank() }
            ?: settings[KEY_TOKEN]?.takeIf { it.isNotBlank() }

    /** True when the token came from the plaintext fallback rather than the keychain. */
    val tokenIsPlaintext: Boolean
        get() = secrets.get(KEY_TOKEN).isNullOrBlank() && !settings[KEY_TOKEN].isNullOrBlank()

    fun promoteTokenToKeychain() {
        val plain = settings[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return
        secrets.set(KEY_TOKEN, plain)
        settings[KEY_TOKEN] = ""
    }

    val isConfigured: Boolean get() = baseUrl != null && token != null

    private companion object {
        const val KEY_URL = "baseUrl"
        const val KEY_TOKEN = "token"
        const val KEY_INSECURE = "allowUnauthorized"
    }
}
