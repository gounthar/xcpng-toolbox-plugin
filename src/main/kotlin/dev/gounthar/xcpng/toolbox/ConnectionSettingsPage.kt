package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import com.jetbrains.toolbox.api.ui.components.UiPage
import com.jetbrains.toolbox.api.ui.components.ValidationResult
import dev.gounthar.xcpng.toolbox.xo.XoPowerState
import dev.gounthar.xcpng.toolbox.xo.XoVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How to reach one VM over SSH, when the pool cannot say.
 *
 * The address half of this exists because of a measurement on the lab pool: `mainIpAddress` is a
 * *last-known* value, trustworthy only while a VM is running, and on the lab pool only 1 of 4
 * running VMs reported one at all, the one that did being XOA itself. A guest with no agent
 * reports nothing, forever, and no amount of waiting changes that. Letting the address be typed
 * turns "unreachable" into "reachable once you say where", which is the difference between a
 * plugin that lists VMs and one that connects to them.
 *
 * The username half exists for a harder reason: **no XO field carries it at all.** It is not a
 * detection problem that better code would solve, so configuration is the only possible answer.
 */
class ConnectionSettingsPage(
    private val vm: XoVm,
    private val settings: XoSettings,
    private val i18n: LocalizableStringFactory,
    private val onSaved: () -> Unit,
) : UiPage(MutableStateFlow(i18n.pnotr("Connection: \"${vm.nameLabel}\""))) {

    private val userField = TextField(
        i18n.ptrl("SSH username"),
        settings.sshUserFor(vm.uuid).orEmpty(),
        TextType.General,
        true,
        null,
        i18n.pnotr(settings.defaultSshUser?.let { "Default: $it" } ?: "No pool default set"),
    ) { ValidationResult.Valid }

    /**
     * The placeholder shows what the pool reports, so an empty field is visibly "use that".
     *
     * It deliberately reads differently for a halted VM. A stale address is the trap this whole
     * area is built around (4 of 10 VMs on the lab pool were halted and still serving one), so
     * showing it here without saying it is stale would be handing the user the exact wrong value
     * to copy into the field below.
     */
    private val hostField = TextField(
        i18n.ptrl("Address override"),
        settings.sshHostFor(vm.uuid).orEmpty(),
        TextType.General,
        false,
        null,
        i18n.pnotr(hostPlaceholder()),
    ) { ValidationResult.Valid }

    private val portField = TextField(
        i18n.ptrl("SSH port"),
        settings.sshPortFor(vm.uuid).takeIf { it != XoSettings.DEFAULT_SSH_PORT }?.toString().orEmpty(),
        TextType.Integer,
        false,
        null,
        i18n.pnotr(XoSettings.DEFAULT_SSH_PORT.toString()),
    ) { ValidationResult.Valid }

    override val fields: StateFlow<List<UiField>> =
        MutableStateFlow(listOf(userField, hostField, portField))

    override val description: LocalizableString = i18n.pnotr(
        "Leave the address empty to use whatever the pool reports while the VM is running. " +
            "Set it for a guest with no XCP-ng agent, which reports no address at all. " +
            "Keys and known hosts come from your own SSH configuration.",
    )

    override val actionButtons: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            object : RunnableActionDescription {
                override val label: LocalizableString = i18n.ptrl("Save")

                /**
                 * Rewrites what was typed into the fields it belongs in, then stores it.
                 *
                 * It does not reject, and that is a decision forced by measurement rather than a
                 * preference. Rejecting needs a way to say why, and on this Toolbox build a
                 * `UiPage` has none: the field validator's `ValidationResult.Invalid` renders
                 * nothing, a `ValidationErrorField` in [fields] renders nothing, and
                 * `ToolboxUi.showInfoPopup` renders nothing either, all three measured on
                 * 2026-08-19 by typing `root@192.168.1.99` and pressing Save. The same popup call
                 * works from a row's action menu, so the difference is that a page is open over
                 * it.
                 *
                 * A refusal nobody can see is worse than the bug it replaced: the value stayed
                 * out of `settings.json`, which was correct, and the person testing it reported it
                 * as saved. So the failure case is removed instead of reported. `root@host` is not
                 * ambiguous about what was meant, and the split is visible on the fields the next
                 * time the page is opened.
                 */
                override fun run() {
                    normaliseFields()
                    settings.setSshOverrides(
                        vm.uuid,
                        user = userField.textState.value,
                        host = hostField.textState.value,
                        port = portField.textState.value.trim().toIntOrNull(),
                    )
                    onSaved()
                }
            },
        ),
    )

    /**
     * Moves a username or a port out of the address field and into its own.
     *
     * Writes back into the fields rather than only into the stored value, so the page carries the
     * corrected form if it is shown again: the fields are the one part of a `UiPage` that has
     * been seen to render reliably.
     *
     * The rules themselves live in [normalise], which is a pure function of three strings. Keeping
     * them out of here is what makes them testable: constructing this page needs a `UiPage`, a
     * `LocalizableStringFactory` and three `TextField`s, none of which a unit test can supply
     * without standing up half of Toolbox.
     */
    private fun normaliseFields() {
        val normalised = normalise(
            user = userField.textState.value,
            host = hostField.textState.value,
            port = portField.textState.value,
        )
        userField.textState.value = normalised.user
        hostField.textState.value = normalised.host
        portField.textState.value = normalised.port
    }

    private fun hostPlaceholder(): String {
        val reported = vm.mainIpAddress
        return when {
            reported != null -> "Pool reports $reported"
            vm.powerState == XoPowerState.RUNNING -> "Running, but reports no address"
            else -> "VM is not running, so the pool reports nothing live"
        }
    }

    /** What the three connection fields hold, before or after [normalise]. */
    internal data class Fields(val user: String, val host: String, val port: String)

    companion object {
        /**
         * The address field, rewritten into the three fields it was trying to be.
         *
         * Pure on purpose; see [normaliseFields]. Every rule here exists because the address field
         * is the one place a user types free text, and what they type is usually a working SSH
         * argument (`root@host`, `host:2222`) rather than a bare address.
         *
         * Nothing is rejected. That is forced rather than preferred: a `UiPage` on this Toolbox
         * build has no way to say why it refused, measured three ways on 2026-08-19, so a refusal
         * would be silent and would read as a successful save.
         */
        internal fun normalise(user: String, host: String, port: String): Fields {
            var resolvedUser = user

            // Normalised first, not last. A port field holding something unusable is not a port,
            // and treating it as one made it win against a real port typed in the address: the
            // address's `:2222` was stripped off the host and then discarded because the field it
            // would have gone into was not blank. Both values were lost, silently.
            var resolvedPort = port.asPortOrBlank()

            var resolvedHost = host.firstToken()

            resolvedHost.substringBefore('@', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.let { typedUser ->
                    resolvedHost = resolvedHost.substringAfter('@')
                    // An explicit username already in its own field wins. The prefix still comes
                    // off the address either way, because it can never be part of one.
                    if (resolvedUser.isBlank()) resolvedUser = typedUser
                }

            splitHostAndPort(resolvedHost)?.let { (bareHost, typedPort) ->
                resolvedHost = bareHost
                if (resolvedPort.isBlank()) resolvedPort = typedPort
            }

            return Fields(resolvedUser, resolvedHost, resolvedPort)
        }

        /** The port if it is one, blank otherwise. Never a guess. */
        private fun String.asPortOrBlank(): String =
            trim().toIntOrNull()?.takeIf { it in 1..65535 }?.toString().orEmpty()

        /**
         * The address out of whatever was pasted into the field.
         *
         * Splits on **any** whitespace, not just a space: a paste carrying a tab or a wrapped
         * newline otherwise left the rest of the command sitting in the host.
         *
         * The `ssh ...` case is the one that bit hardest. Taking the first token of
         * `ssh root@vm.local` yields `ssh`, which is not an error anywhere: it is stored, and the
         * page reports a successful save of a hostname that cannot resolve. Since a `UiPage` here
         * has no way to report a refusal, an unparseable paste returns **blank** instead: a field
         * that is visibly empty says "nothing was saved", which is the honest answer and the one
         * the user can act on.
         */
        private fun String.firstToken(): String {
            val tokens = trim().split(WHITESPACE).filter { it.isNotEmpty() }
            val first = tokens.firstOrNull().orEmpty()
            if (!first.equals("ssh", ignoreCase = true)) return first

            val rest = tokens.drop(1)
            return rest.firstOrNull { it.contains('@') }
                // `ssh vm.local` is unambiguous. `ssh -p 2222 vm.local` is not, and picking a
                // token out of it would store the port as the hostname.
                ?: rest.singleOrNull()
                ?: ""
        }

        /**
         * A host and a port, when the address carries one. Null when it does not.
         *
         * Bracketed IPv6 is handled explicitly rather than merely excluded. Skipping anything
         * starting with `[` protects a bare `[::1]`, but it also silently left the `:2222` of
         * `[::1]:2222` (the standard RFC 3986 form) glued to the host, so SSH would dial that
         * whole string on port 22.
         */
        private fun splitHostAndPort(host: String): Pair<String, String>? {
            if (host.startsWith("[")) {
                val close = host.indexOf(']')
                if (close < 0 || close + 1 >= host.length || host[close + 1] != ':') return null
                val port = host.substring(close + 2).asPortOrBlank()
                return if (port.isEmpty()) null else host.substring(0, close + 1) to port
            }
            // Exactly one colon separates a host from a port. More than one is an unbracketed
            // IPv6 literal, which is left alone rather than guessed at.
            if (host.count { it == ':' } != 1) return null
            val port = host.substringAfter(':').asPortOrBlank()
            return if (port.isEmpty()) null else host.substringBefore(':') to port
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
