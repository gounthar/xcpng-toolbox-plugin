package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.CheckboxField
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import com.jetbrains.toolbox.api.ui.components.UiPage
import com.jetbrains.toolbox.api.ui.components.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

/**
 * Which pool to talk to, and how to authenticate.
 *
 * This is what replaces "seed `settings.json` by hand and restart Toolbox". It is also what makes
 * the plaintext token unnecessary: [XoSettings.setToken] writes through [com.jetbrains.toolbox.api.core.PluginSecretStore]
 * to the OS keychain, which is reachable from inside Toolbox and was never reachable from WSL,
 * which is the whole reason the plaintext fallback existed.
 *
 * Shown two ways, and the distinction matters. [XcpngRemoteProvider.getOverrideUiPage] returns it
 * *instead of* the environment list while the plugin is unconfigured, so a fresh install lands on
 * a form rather than on an empty list with an instruction in the corner. Once configured it is
 * reachable from the provider's own action menu, for editing.
 */
class PoolSettingsPage(
    private val settings: XoSettings,
    private val i18n: LocalizableStringFactory,
    private val onSaved: () -> Unit,
) : UiPage(MutableStateFlow(i18n.ptrl("XCP-ng pool"))) {

    private val urlField = TextField(
        i18n.ptrl("Xen Orchestra URL"),
        settings.baseUrl.orEmpty(),
        TextType.General,
        true,
        null,
        i18n.pnotr("https://xoa.example.com"),
    ) { validateUrl(it) }

    /**
     * Deliberately not pre-filled with the stored token.
     *
     * Reading a secret back out of the keychain to paint it into a form puts it on screen, in a
     * screen recording, and over anyone's shoulder, to save typing it once. Blank means "leave
     * what is stored alone"; the placeholder says so, and [save] only writes when something was
     * actually typed.
     */
    private val tokenField = TextField(
        i18n.ptrl("REST API token"),
        "",
        TextType.Password,
        false,
        null,
        i18n.ptrl(if (settings.token != null) "Stored — type to replace" else "Required"),
    ) { ValidationResult.Valid }

    private val insecureField =
        CheckboxField(settings.allowUnauthorized, i18n.ptrl("Accept a self-signed certificate"))

    /**
     * The pool-wide login name. See [XoSettings.defaultSshUser] for why this is not per-VM only.
     *
     * Optional here rather than required, because it is not needed to *list* a pool — only to
     * connect to a VM — and forcing it would block the setup form on a value the user may not
     * know yet.
     */
    private val userField = TextField(
        i18n.ptrl("Default SSH username"),
        settings.defaultSshUser.orEmpty(),
        TextType.General,
        false,
        null,
        i18n.pnotr("root"),
    ) { ValidationResult.Valid }

    override val fields: StateFlow<List<UiField>> =
        MutableStateFlow(listOf(urlField, tokenField, insecureField, userField))

    override val description: LocalizableString = i18n.pnotr(
        "Create a token in Xen Orchestra under your own user, or with " +
            "`xo-cli token.create`. The token is stored in the OS keychain, not in this " +
            "plugin's settings file.",
    )

    override val actionButtons: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            object : RunnableActionDescription {
                override val label: LocalizableString = i18n.ptrl("Save")
                override val shouldClosePage: Boolean = true
                override fun run() = save()
            },
        ),
    )

    private fun save() {
        settings.baseUrl = urlField.textState.value
        settings.allowUnauthorized = insecureField.checkedState.value
        settings.defaultSshUser = userField.textState.value
        // Blank is "unchanged", per the note on tokenField — not "erase the token".
        tokenField.textState.value.trim().takeIf { it.isNotEmpty() }?.let { settings.setToken(it) }
        // A token seeded into settings.json before this page existed is moved to the keychain on
        // the first save, so the plaintext copy does not outlive the reason it was there.
        settings.promoteTokenToKeychain()
        // The page instance outlives the save — the provider holds one and reuses it, so that a
        // half-filled form survives Toolbox asking for it again. That reuse is right for every
        // other field, whose contents are exactly what was last written, and wrong for this one:
        // leaving the typed token in the field means reopening Settings paints the secret back
        // onto the screen, and the placeholder would still be inviting a value that now exists.
        tokenField.textState.value = ""
        tokenField.placeholderState.value = i18n.ptrl("Stored — type to replace")
        onSaved()
    }

    private fun validateUrl(raw: String): ValidationResult {
        val text = raw.trim()
        if (text.isEmpty()) return invalid("Required.")
        val uri = runCatching { URI(text) }.getOrNull() ?: return invalid("Not a valid URL.")
        // Checked rather than silently prepended. XO answers on both schemes and guessing wrong
        // produces a connection failure whose message says nothing about the scheme.
        if (uri.scheme != "http" && uri.scheme != "https") {
            return invalid("Include the scheme, for example https://xoa.example.com")
        }
        if (uri.host.isNullOrBlank()) return invalid("No host in that URL.")
        return ValidationResult.Valid
    }

    private fun invalid(message: String) = ValidationResult.Invalid(i18n.pnotr(message))
}
