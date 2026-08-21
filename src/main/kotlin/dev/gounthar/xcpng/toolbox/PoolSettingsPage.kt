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
import dev.gounthar.xcpng.toolbox.xo.SELF_SIGNED_CHECKBOX_LABEL
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
 *
 * `internal` because nothing outside this plugin constructs it, and because [Attempt] is a
 * constructor parameter type: a public constructor may not expose an internal one, and widening
 * [Attempt] to satisfy that would publish a type carrying a token for no reason at all.
 */
internal class PoolSettingsPage(
    private val settings: XoSettings,
    private val i18n: LocalizableStringFactory,
    /**
     * How a rejected save says why. Raised by the caller, which owns both a scope and a
     * [com.jetbrains.toolbox.api.ui.ToolboxUi]; `showInfoPopup` suspends and this class has neither.
     */
    private val showProblem: (String) -> Unit,
    private val onSaved: () -> Unit,
    /**
     * Runs one read against the appliance and reports what happened.
     *
     * Here for the same reason as [showProblem]: the call is blocking network I/O and its answer
     * arrives in a popup, so it needs a scope and a
     * [com.jetbrains.toolbox.api.ui.ToolboxUi], and this class holds neither. What this class does
     * own is the decision of *what* to test, which is [Attempt].
     */
    private val testConnection: (Attempt) -> Unit,
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
        // Label shared with the failure message that tells people to tick it. Change them
        // together — which is only possible because it is one string. See the constant.
        CheckboxField(settings.allowUnauthorized, i18n.ptrl(SELF_SIGNED_CHECKBOX_LABEL))

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
                /**
                 * Closes even when it refuses, which looks wrong and is the only thing that works.
                 *
                 * A popup raised from inside a `UiPage` is queued until that page closes.
                 * An earlier version kept the page open on invalid input *and* raised a
                 * popup, which are mutually exclusive: the page never closed, so the message never
                 * appeared, and a save that silently did nothing read as a save that worked.
                 *
                 * Nothing is lost by closing here. [XcpngRemoteProvider] holds this page instance,
                 * so reopening Settings shows the text that was typed, still in the fields.
                 */
                override fun run() {
                    val problem = firstProblem()
                    if (problem != null) {
                        showProblem(problem)
                        return
                    }
                    save()
                }
            },
            object : RunnableActionDescription {
                override val label: LocalizableString = i18n.ptrl("Test connection")

                /**
                 * Tests what is **typed**, and does not save it.
                 *
                 * Reading the stored settings instead would make the button useless exactly when
                 * it is wanted — on a fresh install nothing is stored yet, and the whole point is
                 * to find out whether what was just typed works before committing to it.
                 *
                 * It closes the page like every other action, and that is forced rather than
                 * chosen: a popup raised while a `UiPage` is open is queued until that page
                 * closes, so a button that stayed open could never report its own result. See the
                 * note on Save. Nothing typed is lost, because
                 * [XcpngRemoteProvider] holds this page instance and reopening Settings shows the
                 * same fields; the success message says the settings were not saved, because a
                 * page that closes on a green result otherwise reads as one that saved.
                 */
                override fun run() {
                    val problem = firstProblem()
                    if (problem != null) {
                        showProblem(problem)
                        return
                    }
                    testConnection(attempt())
                }
            },
        ),
    )

    /**
     * The three values a test needs, taken from the form rather than from storage.
     *
     * [typedToken] is null when the field was left blank, which per the note on [tokenField]
     * means "use the stored one" rather than "there is no token" — resolving that needs the
     * keychain, so it is left to the caller that already has it.
     */
    internal data class Attempt(
        val baseUrl: String,
        val typedToken: String?,
        val allowUnauthorized: Boolean,
    ) {
        /**
         * Whether this is the configuration already in use, rather than an edit being tried out.
         *
         * It decides one sentence, and that sentence is wrong half the time without it. Telling
         * somebody who just typed a new URL that nothing was saved is the whole point; telling
         * somebody who opened Settings and pressed the button to check an already-working pool
         * that nothing was saved warns them about work they never did, and invites them to press
         * Save to keep values that are already kept. Measured on a real click, 2026-08-21.
         *
         * A blank token field is what "unchanged" looks like — per the note on [tokenField] it
         * means "use the stored one". Somebody retyping the identical token counts as an edit
         * here, which is not distinguishable without comparing secrets and is harmless: the worst
         * case is the more cautious of the two sentences.
         */
        fun matchesStored(storedUrl: String?, storedAllowUnauthorized: Boolean): Boolean =
            typedToken == null &&
                baseUrl == storedUrl?.trim() &&
                allowUnauthorized == storedAllowUnauthorized
    }

    private fun attempt() = Attempt(
        baseUrl = urlField.textState.value.trim(),
        typedToken = tokenField.textState.value.trim().takeIf { it.isNotEmpty() },
        allowUnauthorized = insecureField.checkedState.value,
    )

    private fun save() {
        // Trimmed, because urlProblem validates the trimmed form and storing the untrimmed one
        // would let a trailing space pass the form and then land in the middle of a request URL,
        // where it fails as something that looks nothing like a stray keystroke. It also keeps
        // what "Test connection" tried and what Save wrote as the same string.
        settings.baseUrl = urlField.textState.value.trim()
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

    /**
     * The first thing wrong with the form, or null when it is safe to store.
     *
     * The token counts as present if one is already stored, because the field is deliberately
     * blank in that case — see the note on [tokenField]. Getting this backwards would make an
     * existing installation unable to change its URL without retyping its token.
     */
    private fun firstProblem(): String? {
        urlProblem(urlField.textState.value)?.let { return it }
        if (tokenField.textState.value.isBlank() && settings.token == null) {
            return "A REST API token is required."
        }
        return null
    }

    private fun urlProblem(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "A Xen Orchestra URL is required."
        val uri = runCatching { URI(text) }.getOrNull() ?: return "That is not a valid URL."
        // Checked rather than silently prepended. XO answers on both schemes and guessing wrong
        // produces a connection failure whose message says nothing about the scheme.
        if (uri.scheme != "http" && uri.scheme != "https") {
            return "Include the scheme, for example https://xoa.example.com"
        }
        if (uri.host.isNullOrBlank()) return "That URL has no host in it."
        return null
    }

    private fun validateUrl(raw: String): ValidationResult =
        urlProblem(raw)?.let { ValidationResult.Invalid(i18n.pnotr(it)) } ?: ValidationResult.Valid
}
