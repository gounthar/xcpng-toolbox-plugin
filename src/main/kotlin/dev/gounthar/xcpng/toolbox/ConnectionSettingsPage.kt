package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.TextField
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiField
import com.jetbrains.toolbox.api.ui.components.UiPage
import com.jetbrains.toolbox.api.ui.components.ValidationErrorField
import com.jetbrains.toolbox.api.ui.components.ValidationResult
import dev.gounthar.xcpng.toolbox.xo.XoVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How to reach one VM over SSH, when the pool cannot say.
 *
 * The address half of this exists because of the measurement in CLAUDE.md: `mainIpAddress` is a
 * *last-known* value, trustworthy only while a VM is running, and on the lab pool only 1 of 4
 * running VMs reported one at all — the one that did being XOA itself. A guest with no agent
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
) : UiPage(MutableStateFlow(i18n.pnotr("Connection — \"${vm.nameLabel}\""))) {

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
     * area is built around — 4 of 10 VMs on the lab pool were halted and still serving one — so
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
    ) { asValidation(hostProblem(it)) }

    private val portField = TextField(
        i18n.ptrl("SSH port"),
        settings.sshPortFor(vm.uuid).takeIf { it != XoSettings.DEFAULT_SSH_PORT }?.toString().orEmpty(),
        TextType.Integer,
        false,
        null,
        i18n.pnotr(XoSettings.DEFAULT_SSH_PORT.toString()),
    ) { asValidation(portProblem(it)) }

    /**
     * Where a rejected save says why.
     *
     * Field-level validators are not enough on their own. `RunnableActionDescription.validate()`
     * defaults to true and the address field's validator does reject `user@host`, yet a save with
     * that value in it still wrote it to disk — measured 2026-08-19 by typing it and then reading
     * `settings.json`. Whatever the field validator gates, it is not the action, so the check that
     * actually protects the stored value has to run inside the button.
     */
    private val errorField = ValidationErrorField(i18n.pnotr(""))

    override val fields: StateFlow<List<UiField>> =
        MutableStateFlow(listOf(userField, hostField, portField, errorField))

    override val description: LocalizableString = i18n.pnotr(
        "Leave the address empty to use whatever the pool reports while the VM is running. " +
            "Set it for a guest with no XCP-ng agent, which reports no address at all. " +
            "Keys and known hosts come from your own SSH configuration.",
    )

    override val actionButtons: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            object : RunnableActionDescription {
                override val label: LocalizableString = i18n.ptrl("Save")

                // A getter, not a constant: it is answered from whatever is in the fields at the
                // moment of the click, so an invalid save leaves the page up with its reason on it
                // rather than closing and discarding what the user typed.
                override val shouldClosePage: Boolean get() = firstProblem() == null

                override fun run() {
                    val problem = firstProblem()
                    if (problem != null) {
                        errorField.textState.value = i18n.pnotr(problem)
                        return
                    }
                    errorField.textState.value = i18n.pnotr("")
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

    /** The first thing wrong with the form, or null when it is safe to store. */
    private fun firstProblem(): String? =
        hostProblem(hostField.textState.value) ?: portProblem(portField.textState.value)

    private fun hostPlaceholder(): String {
        val reported = vm.mainIpAddress
        return when {
            reported != null -> "Pool reports $reported"
            vm.powerState == dev.gounthar.xcpng.toolbox.xo.XoPowerState.RUNNING ->
                "Running, but reports no address"
            else -> "VM is not running, so the pool reports nothing live"
        }
    }

    /**
     * The checks themselves return the message, and both the field validator and the save button
     * ask them. Duplicating the rules in a second place is how the two drift until the form says
     * one thing and the stored value is another.
     */
    private fun hostProblem(raw: String): String? {
        val text = raw.trim()
        // Empty is the normal case: it means "fall back to what the pool reports".
        if (text.isEmpty()) return null
        // A pasted "user@host" or "host:port" would be stored verbatim and then handed to SSH as a
        // hostname, which fails somewhere far from here with a DNS error. Caught at the source.
        if ("@" in text) return "Put the username in the field above, not in the address."
        if (":" in text && !text.startsWith("[")) {
            return "Put the port in the field below. Bracket a literal IPv6 address."
        }
        if (text.any { it.isWhitespace() }) return "An address cannot contain spaces."
        return null
    }

    private fun portProblem(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val port = text.toIntOrNull() ?: return "The port must be a number."
        if (port !in 1..65535) return "A port is between 1 and 65535."
        return null
    }

    private fun asValidation(problem: String?): ValidationResult =
        problem?.let { ValidationResult.Invalid(i18n.pnotr(it)) } ?: ValidationResult.Valid
}
