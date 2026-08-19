package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.EnvironmentVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.environments.EnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.environments.SshEnvironmentContentsView
import com.jetbrains.toolbox.api.remoteDev.ssh.SshConnectionInfo
import com.jetbrains.toolbox.api.remoteDev.states.CustomRemoteEnvironmentStateV2
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentDescription
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateIcons
import com.jetbrains.toolbox.api.remoteDev.states.RemoteEnvironmentState
import com.jetbrains.toolbox.api.remoteDev.states.StandardRemoteEnvironmentState
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.TextType
import com.jetbrains.toolbox.api.ui.components.UiComponents
import dev.gounthar.xcpng.toolbox.xo.XoClient
import dev.gounthar.xcpng.toolbox.xo.XoPowerState
import dev.gounthar.xcpng.toolbox.xo.XoSnapshot
import dev.gounthar.xcpng.toolbox.xo.XoVm
import dev.gounthar.xcpng.toolbox.xo.resumeVerb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One VM on a pool, presented to Toolbox as an environment.
 *
 * Toolbox deliberately gives a provider no lifecycle of its own: [RemoteEnvironmentAbility] runs
 * to rename, always-connected and skip-delete-confirmation, and nothing else. Start and stop are
 * the plugin's own calls surfaced through [actionsList], which is exactly why this project is a
 * Toolbox provider rather than a Gateway connector.
 *
 * The instance is long-lived and mutated in place by [update]. That is not incidental: an
 * environment recreated on every pool refresh would lose the transitional state an action just
 * set, so a click on Start would flicker back to Halted a moment later and read as a broken
 * button. [XcpngRemoteProvider] keeps these keyed by UUID for the same reason.
 */
class XcpngVmEnvironment(
    initialVm: XoVm,
    private val i18n: LocalizableStringFactory,
    private val logger: Logger,
    private val ui: ToolboxUi,
    private val uiComponents: UiComponents,
    private val statePalette: EnvironmentStateColorPalette,
    private val settings: XoSettings,
    private val scope: CoroutineScope,
    /**
     * A fresh client per call rather than a shared one. [dev.gounthar.xcpng.toolbox.xo.XoRestClient]
     * holds no session — its `close()` is a no-op and every call opens its own connection — so
     * this costs nothing and keeps the environment from outliving a client whose token has since
     * been rotated in settings.
     */
    private val clientFactory: () -> XoClient,
) : RemoteProviderEnvironment(initialVm.uuid) {

    private var vm: XoVm = initialVm

    init {
        // nameFlow rather than the deprecated `name` setter, which the API now routes here anyway.
        nameFlow.value = initialVm.nameLabel
    }

    override val state: MutableStateFlow<RemoteEnvironmentState> =
        MutableStateFlow(initialVm.powerState.toToolboxState())

    override val description: MutableStateFlow<EnvironmentDescription> =
        MutableStateFlow(initialVm.toDescription())

    override val actionsList: MutableStateFlow<List<ActionDescription>> =
        MutableStateFlow(emptyList())

    init {
        // After the flows exist, or the first build would publish into an uninitialised field.
        rebuildActions()
    }

    /**
     * Adopt a newer view of this VM, from a pool refresh or from re-reading it after an action.
     *
     * Skips the write when nothing the UI shows has changed, so a refresh while the user is
     * looking at the list does not churn three flows per VM for no reason.
     */
    fun update(fresh: XoVm) {
        if (fresh == vm) return
        vm = fresh
        nameFlow.value = fresh.nameLabel
        state.value = fresh.powerState.toToolboxState()
        description.value = fresh.toDescription()
        rebuildActions()
    }

    /**
     * Toolbox reaches a VM over SSH, so this is the [SshEnvironmentContentsView] variant.
     *
     * **This method answers unconditionally and decides nothing.** All three reasons are about
     * *when* Toolbox calls it rather than what it returns, and each was paid for once.
     *
     * **It must never fabricate an endpoint.** This used to return a hardcoded `127.0.0.1:22` as
     * `root`, on the theory that a placeholder is harmless until the real thing arrives. It is
     * not: Toolbox takes the address seriously, opens an SSH handshake against the user's own
     * machine, and sits on it. The modal that appears has a Cancel button that cannot unwind a
     * connect already in flight, so the whole application hangs and has to be killed from the task
     * manager. Measured 2026-08-19 14:47, and it cost a force-kill mid-session.
     *
     * **It must never touch [ui].** An early fix showed an explanatory popup from here. Toolbox
     * calls this on *every* environment of its own accord rather than only on the one the user
     * clicked, so ten VMs meant ten modals to dismiss, on every refresh. This is not a
     * user-initiated callback and nothing modal belongs in it.
     *
     * **It must never throw.** Throwing here refuses during *enumeration*, which put one stack
     * trace per VM per refresh into the log at ERROR — eleven per launch on this pool, for VMs
     * nobody had touched, through to 16:12 on 2026-08-19. Anything that can fail belongs one
     * level down in [VmSshContentsView.getConnectionInfo], which Toolbox calls only when somebody
     * actually connects.
     */
    override suspend fun getContentsView(): EnvironmentContentsView = VmSshContentsView()

    /**
     * Where the VM's SSH endpoint is worked out, or the reason there isn't one.
     *
     * Resolved on demand rather than when the view was constructed, because Toolbox caches a
     * contents view and a VM's state moves underneath it. A VM started from its own row menu a
     * moment after this was made would otherwise still be refused as halted.
     *
     * The address may come from the pool or from the user (see [ConnectionSettingsPage]); the
     * username can only ever come from the user, because no Xen Orchestra field carries one.
     */
    private inner class VmSshContentsView : SshEnvironmentContentsView {
        override suspend fun getConnectionInfo(): SshConnectionInfo {
            // A halted VM is refused before the address is even considered. An override typed for
            // a VM that is switched off is not wrong, it is just not connectable yet, and "start
            // it first" is the actionable thing to say.
            if (vm.powerState != XoPowerState.RUNNING) {
                throw CannotConnectYet(
                    "\"${vm.nameLabel}\" is ${vm.powerState.name.lowercase()}. Start it first.",
                )
            }
            // The override wins over the pool. That ordering is the point of having one: the pool
            // is the thing that could not answer.
            val host = settings.sshHostFor(vm.uuid) ?: vm.mainIpAddress ?: throw CannotConnectYet(
                "\"${vm.nameLabel}\" is running but reports no address to the pool, which " +
                    "usually means the guest has no XCP-ng agent. Set one under " +
                    "\"Connection settings…\" in this VM's menu.",
            )
            val user = settings.sshUserFor(vm.uuid) ?: settings.defaultSshUser
                ?: throw CannotConnectYet(
                    "\"${vm.nameLabel}\" is reachable at $host, but no SSH username is set. " +
                        "Nothing in Xen Orchestra records one, so it has to be given under " +
                        "\"Connection settings…\" in this VM's menu, or as a pool-wide default " +
                        "in the provider's settings.",
                )
            val sshPort = settings.sshPortFor(vm.uuid)
            logger.info("XCP-ng: connecting to ${vm.uuid} as $user@$host:$sshPort.")
            // Only host, port and userName are abstract. Everything about authentication is a
            // default and the defaults are the ones we want, verified by disassembling the
            // installed runtime on 2026-08-19: shouldUseSystemConfiguration and
            // shouldUseSystemSshAgent are both true, and shouldAskForPassword is true. So the
            // user's own ~/.ssh/config, keys and agent apply, with a password prompt as the
            // fallback, and this plugin never handles a credential. See CLAUDE.md.
            return object : SshConnectionInfo {
                override val host: String = host
                override val port: Int = sshPort
                override val userName: String = user
            }
        }
    }

    override fun setVisible(visibilityState: EnvironmentVisibilityState) {
        // The provider refreshes the whole pool when its page becomes visible, which covers this.
    }

    // ---------------------------------------------------------------- actions

    /**
     * The buttons Toolbox shows for this VM, derived from its power state.
     *
     * Offering only what the current state allows is the point. XO answers a `start` on a
     * suspended VM with an error, and an error a user provoked by clicking a button we chose to
     * show reads as a broken plugin rather than as a wrong verb.
     */
    private fun rebuildActions() {
        val actions = mutableListOf<ActionDescription>()

        // One entry covers halted, suspended and paused; the verb differs per state and the
        // mapping lives in XoPowerState.resumeVerb so there is exactly one of it.
        vm.powerState.resumeVerb?.let { verb ->
            actions += action(
                when (vm.powerState) {
                    XoPowerState.SUSPENDED -> "Resume"
                    XoPowerState.PAUSED -> "Unpause"
                    else -> "Start"
                },
                busyState = StandardRemoteEnvironmentState.Activating,
                // Also measured: clicking Start on a VM that somebody else already started gives
                // VM_BAD_POWER_STATE(ref, expected, actual). The list is only as fresh as the last
                // refresh, so this is an ordinary race rather than a fault.
                hint = { detail ->
                    // Parenthesised deliberately: `.takeIf` binds to the literal it follows, so
                    // without these the hint is never null and reads "... re-read from null".
                    ("The VM is no longer in the state this list showed. It has been re-read from " +
                        "the pool.")
                        .takeIf { "VM_BAD_POWER_STATE" in detail }
                },
            ) { client -> verb(client, vm) }
        }

        if (vm.powerState == XoPowerState.RUNNING) {
            actions += action(
                "Shut down",
                busyState = StandardRemoteEnvironmentState.Hibernating,
                // Measured against alpine-test-3 on 2026-08-19: XO answers 500 with the raw XAPI
                // code and nothing a user can act on. The code is the whole diagnosis, so say what
                // it means and what to click instead.
                hint = { detail ->
                    ("The guest has no XCP-ng agent listening, so it cannot be asked to shut itself " +
                        "down. Use \"Force shut down\" instead.")
                        .takeIf { "VM_LACKS_FEATURE" in detail }
                },
            ) { client -> client.cleanShutdown(vm) }
            // Not an advanced extra. `clean_shutdown` asks the guest to shut itself down and
            // fails when nothing is listening, and on the lab pool only 1 of 4 running VMs was
            // reporting to the host at all. Without this, those VMs cannot be stopped from here.
            actions += action(
                "Force shut down",
                busyState = StandardRemoteEnvironmentState.Hibernating,
                dangerous = true,
                confirm = "Cut the power to \"${vm.nameLabel}\"? The guest gets no chance to " +
                    "flush anything to disk. Use this when a clean shut down has failed.",
            ) { client -> client.hardShutdown(vm) }
        }

        actions += snapshotAction()
        actions += revertAction()
        actions += connectionSettingsAction()

        actionsList.value = actions
    }

    /** Take a snapshot, naming it. Offered in every state; XO snapshots a running VM happily. */
    private fun snapshotAction(): ActionDescription = action("Take snapshot…", busyState = null) { client ->
        val typed = ui.showTextInputPopup(
            i18n.ptrl("Take a snapshot"),
            i18n.pnotr("Snapshot \"${vm.nameLabel}\"."),
            i18n.ptrl("Snapshot name"),
            TextType.General,
            i18n.ptrl("Take snapshot"),
            i18n.ptrl("Cancel"),
        )
        if (typed == null) {
            logger.info("XCP-ng: snapshot of ${vm.uuid} cancelled.")
            return@action
        }
        // Clicking through without typing is not a cancel, but a nameless snapshot is one nobody
        // can identify in six weeks, so it gets a timestamped name rather than an empty one.
        val label = typed.trim().ifEmpty { "toolbox-${System.currentTimeMillis() / 1000}" }
        val id = client.snapshot(vm, label)
        logger.info("XCP-ng: snapshotted ${vm.uuid} as \"$label\" (id ${id ?: "not reported"}).")
    }

    /**
     * Roll back to a snapshot, chosen from a dropdown on a [SnapshotPickerPage].
     *
     * [busyState] is deliberately null here even though this action does change the VM. The busy
     * state is set by hand *after* the user has chosen, because the picker can sit open for as
     * long as it takes someone to read a list of snapshot names, and a row showing "Restarting"
     * that whole time is a lie about what the pool is doing.
     */
    private fun revertAction(): ActionDescription = action(
        "Revert to snapshot…",
        busyState = null,
        dangerous = true,
    ) { client ->
        val snapshots = client.listSnapshots(vm)
        if (snapshots.isEmpty()) {
            ui.showInfoPopup(
                i18n.ptrl("No snapshots"),
                i18n.pnotr("\"${vm.nameLabel}\" has no snapshots to revert to."),
                i18n.ptrl("OK"),
            )
            return@action
        }
        val chosen = CompletableDeferred<XoSnapshot?>()
        ui.showUiPageSuspending(
            SnapshotPickerPage(vm.nameLabel, snapshots, i18n, uiComponents) { chosen.complete(it) },
        )
        // The page answers exactly once, including on every dismissal path, so this cannot hang.
        val snapshot = chosen.await() ?: run {
            logger.info("XCP-ng: revert of ${vm.uuid} cancelled.")
            return@action
        }
        state.value = StandardRemoteEnvironmentState.Restarting
        client.revertSnapshot(vm, snapshot.id)
        logger.info("XCP-ng: reverted ${vm.uuid} to snapshot ${snapshot.id}.")
    }

    /**
     * Where the username and the address override are set.
     *
     * Offered in every power state on purpose. The natural instinct is to show it only when a
     * connect has already failed, but the case it exists for — a guest with no agent, which will
     * never report an address — is a VM the user knows about in advance and can configure while
     * it is still switched off.
     */
    private fun connectionSettingsAction(): ActionDescription =
        action("Connection settings\u2026", busyState = null) {
            ui.showUiPageSuspending(
                ConnectionSettingsPage(
                    vm,
                    settings,
                    i18n,
                    onSaved = {
                        logger.info("XCP-ng: connection settings saved for ${vm.uuid}.")
                    },
                ),
            )
        }

    /**
     * Builds one button.
     *
     * [busyState] is shown while the call is in flight and then thrown away, because the state
     * that gets published afterwards is re-read from the pool rather than assumed. `sync=true`
     * means XO answers when the work is done, so the window is short — but a Start that takes
     * four seconds with no visible change is indistinguishable from a dead button.
     */
    private fun action(
        label: String,
        busyState: RemoteEnvironmentState?,
        dangerous: Boolean = false,
        confirm: String? = null,
        /**
         * Turns XO's failure text into something a user can act on, or null to show it raw.
         *
         * Takes the detail rather than being a fixed string so it can fire only for the code it
         * explains. A hint that appears under every failure teaches people to ignore hints.
         */
        hint: (String) -> String? = { null },
        block: suspend (XoClient) -> Unit,
    ): ActionDescription = object : RunnableActionDescription {
        override val label: LocalizableString = i18n.ptrl(label)
        override val isDangerous: Boolean = dangerous

        // Runnable, so this returns immediately and the work goes to the plugin's scope. Blocking
        // here would block whatever Toolbox thread dispatches the click.
        override fun run() {
            scope.launch {
                if (confirm != null) {
                    val ok = ui.showOkCancelPopup(
                        i18n.ptrl(label),
                        i18n.pnotr(confirm),
                        i18n.ptrl(label),
                        i18n.ptrl("Cancel"),
                    )
                    if (!ok) return@launch
                }
                if (busyState != null) state.value = busyState
                val failure = runCatching { clientFactory().use { block(it) } }.exceptionOrNull()
                // Re-read before reporting, not after. The pool is the authority on what state the
                // VM ended in — a refused clean shutdown leaves it running — and doing this first
                // means the row behind the popup is already correct when the user dismisses it.
                refreshSelf()
                if (failure == null) return@launch
                logger.error(failure, "XCP-ng: \"$label\" failed on ${vm.uuid}")
                val detail = failure.message ?: failure.toString()
                ui.showInfoPopup(
                    i18n.ptrl("$label failed"),
                    i18n.pnotr(hint(detail)?.let { "$it\n\n$detail" } ?: detail),
                    i18n.ptrl("OK"),
                )
            }
        }
    }

    /**
     * Re-read this VM and republish. Silent on failure: a refresh is not worth a popup.
     *
     * Republishes [state] unconditionally, and that is load-bearing rather than defensive.
     * [update] skips everything when the pool reports no change, but the busy state an action sets
     * before calling out is **not** derived from [vm] — so an action that leaves the VM exactly as
     * it found it would return to a row still showing `Activating` or `Restarting`, with nothing
     * left to ever clear it. Measured 2026-08-19: "Revert to snapshot" on a VM with no snapshots
     * showed its popup and stranded the row on Restarting permanently.
     *
     * Writing the same value back costs nothing — [MutableStateFlow] only emits on a distinct
     * value — so this is free in the ordinary case.
     */
    private suspend fun refreshSelf() {
        runCatching { clientFactory().use { it.getVm(vm.uuid) } }
            .onSuccess { fresh ->
                if (fresh == null) {
                    // Deleted in XO while it was on screen. The provider's next refresh drops it
                    // from the list; until then, say so rather than showing a stale power state.
                    state.value = StandardRemoteEnvironmentState.Deleted
                } else {
                    update(fresh)
                    state.value = fresh.powerState.toToolboxState()
                }
            }
            .onFailure {
                logger.warn(it, "XCP-ng: could not re-read ${vm.uuid} after an action")
                // The re-read is what normally clears a busy state, so a failed one must put the
                // last known state back rather than leaving the row mid-transition for good.
                state.value = vm.powerState.toToolboxState()
            }
    }

    /**
     * XAPI power states mapped onto something Toolbox will render as *settled*.
     *
     * The obvious mapping is straight onto [StandardRemoteEnvironmentState] — Active, Inactive,
     * Hibernated — and that is what this did first. It produced a permanently rotating indicator
     * on every halted row: Toolbox picks the icon for a standard state itself, and for a settled
     * but unreachable one it picks an animated [EnvironmentStateIcons.Connecting]. A row that has
     * finished shutting down then looks identical to one still shutting down, which destroys the
     * only signal the action buttons have.
     *
     * [CustomRemoteEnvironmentStateV2] exists for exactly this: the label, the icon and the
     * reachability flag are ours, and only the colour is looked up so the theme stays Toolbox's.
     *
     * **Transitional states deliberately stay standard.** `Activating`, `Hibernating`,
     * `Restarting` and `Deleted` are set elsewhere and are left as [StandardRemoteEnvironmentState],
     * because there the animation is telling the truth. The rule is: standard while something is
     * happening, custom once it has stopped.
     */
    private fun XoPowerState.toToolboxState(): RemoteEnvironmentState = when (this) {
        XoPowerState.RUNNING -> customState("Running", StandardRemoteEnvironmentState.Active,
            reachable = true, icon = EnvironmentStateIcons.Active)
        // Not Hibernated: that implies saved memory, which is what XAPI calls Suspended.
        XoPowerState.HALTED -> customState("Halted", StandardRemoteEnvironmentState.Inactive,
            reachable = false, icon = EnvironmentStateIcons.Offline)
        XoPowerState.SUSPENDED -> customState("Suspended", StandardRemoteEnvironmentState.Hibernated,
            reachable = false, icon = EnvironmentStateIcons.Hibernated)
        XoPowerState.PAUSED -> customState("Paused", StandardRemoteEnvironmentState.Hibernated,
            reachable = false, icon = EnvironmentStateIcons.Hibernated)
        XoPowerState.UNKNOWN -> customState("Unknown", StandardRemoteEnvironmentState.Unreachable,
            reachable = false, icon = EnvironmentStateIcons.Error)
    }

    /** [colorOf] names which standard state's colour to borrow, nothing more. */
    private fun customState(
        label: String,
        colorOf: StandardRemoteEnvironmentState,
        reachable: Boolean,
        icon: EnvironmentStateIcons,
    ): RemoteEnvironmentState =
        CustomRemoteEnvironmentStateV2(i18n.pnotr(label), statePalette.getColor(colorOf), reachable, icon)

    private fun XoVm.toDescription(): EnvironmentDescription =
        // pnotr is "plain, not translated". A VM's name, state and UUID are not translatable.
        Description(i18n.pnotr("${powerState.name.lowercase()} · ${uuid.take(8)}"))

    private class Description(override val description: LocalizableString) : EnvironmentDescription
}

/**
 * Refusal to open an IDE against a VM, thrown from
 * [XcpngVmEnvironment.RefusedSshContentsView.getConnectionInfo].
 *
 * **The class name is the headline the user reads**, which is the whole reason this type exists.
 * Measured on Toolbox 3.7.0.87111, 2026-08-19: a failed connect puts `Fatal error: <SimpleName>`
 * on the environment row, with the message itself one click away behind a "Troubleshooting" link.
 * `error(...)` throws [IllegalStateException], so the most prominent word shown to somebody who
 * clicked Connect was a Java type.
 *
 * Keep the name a readable phrase, and keep the specifics in the message where the detail view
 * shows them: the three cases this covers — halted, running with no address, running with an
 * address but no known username — cannot all fit in one class name.
 */
class CannotConnectYet(message: String) : IllegalStateException("Cannot connect yet. $message")
