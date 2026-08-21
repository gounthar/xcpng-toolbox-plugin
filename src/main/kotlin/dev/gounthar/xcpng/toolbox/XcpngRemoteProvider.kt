package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableString
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.jetbrains.toolbox.api.ui.actions.ActionDescription
import com.jetbrains.toolbox.api.ui.actions.RunnableActionDescription
import com.jetbrains.toolbox.api.ui.components.UiComponents
import com.jetbrains.toolbox.api.ui.components.UiPage
import dev.gounthar.xcpng.toolbox.xo.VM_COLLECTION
import dev.gounthar.xcpng.toolbox.xo.XoEventStream
import dev.gounthar.xcpng.toolbox.xo.XoRestClient
import dev.gounthar.xcpng.toolbox.xo.xoUnreachableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * The name Toolbox shows for this provider, and the string every user actually reads.
 *
 * It must stay in step with `readableName` in the `generateExtensionJson` task in
 * `build.gradle.kts`. They are two different surfaces and changing one does not change the
 * other: `readableName` names the *plugin*, this names the *provider* in the environment list.
 * PR #6 changed only the manifest and its commit message claimed that field was "the only one
 * every user sees"; the list still read "XCP-ng" on the next launch, verified on screen
 * 2026-08-20. `RemoteProvider.getName()` is final, so this constructor argument is the only
 * way to set it.
 */
private const val PROVIDER_NAME = "XCP-ng (unofficial)"

/** Lists the VMs on a pool as Toolbox environments. */
class XcpngRemoteProvider(
    serviceLocator: ServiceLocator,
) : RemoteProvider(PROVIDER_NAME) {

    private val i18n = serviceLocator.getService(LocalizableStringFactory::class.java)
    private val logger = serviceLocator.getService(Logger::class.java)
    private val scope = serviceLocator.getService(CoroutineScope::class.java)
    private val ui = serviceLocator.getService(ToolboxUi::class.java)
    private val uiComponents = serviceLocator.getService(UiComponents::class.java)
    // Toolbox owns the theme, so a state's colour is looked up rather than chosen. See the
    // state mapping in XcpngVmEnvironment for why the states are custom at all.
    private val statePalette = serviceLocator.getService(EnvironmentStateColorPalette::class.java)

    private val settings = XoSettings(
        serviceLocator.getService(PluginSettingsStore::class.java),
        serviceLocator.getService(PluginSecretStore::class.java),
    )

    private val environmentList =
        MutableStateFlow<LoadableState<List<RemoteProviderEnvironment>>>(
            LoadableState.Value(emptyList()),
        )

    /**
     * Live environments, keyed by VM UUID, reused across refreshes.
     *
     * Rebuilding these each time looked simpler and is wrong once there are actions: an
     * environment carries the transitional state a click just set, so a refresh landing mid-start
     * would replace the object and snap the row back to Halted. The user reads that as the button
     * not working. Coder keeps instances for the same reason.
     */
    private val environmentsByUuid = mutableMapOf<String, XcpngVmEnvironment>()

    /**
     * How an environment gets a client of its own to act through, without holding one open.
     *
     * Reads settings at call time rather than at construction, so a token edited in settings.json
     * takes effect on the next action instead of needing Toolbox restarted.
     */
    private fun newClient(): XoRestClient = XoRestClient(
        baseUrl = settings.baseUrl!!,
        token = settings.token!!,
        allowUnauthorized = settings.allowUnauthorized,
    )

    /** A VM is created in Xen Orchestra, not from an IDE. */
    override val canCreateNewEnvironments: Boolean = false

    /** A pool has many VMs. */
    override val isSingleEnvironment: Boolean = false

    override val environments: Flow<LoadableState<List<RemoteProviderEnvironment>>> = environmentList

    override val noEnvironmentsDescription: String
        get() = if (settings.isConfigured) {
            "No VMs found on this pool."
        } else {
            // Rarely seen now: an unconfigured provider gets the settings form from
            // getOverrideUiPage instead of an empty list. Kept honest for the case where Toolbox
            // renders the list anyway.
            "Not configured yet. Open Settings to enter a Xen Orchestra URL and token."
        }

    /**
     * Toolbox tells us when the user is actually looking, and both the refresh and the event
     * stream hang off that on purpose: holding a connection open to every configured pool in the
     * background is exactly the behaviour an operator would notice and dislike.
     *
     * The refresh still happens on every appearance, and it has to. **Subscribing delivers no
     * initial dump**. Measured 2026-08-21: a subscribed stream sits on `init` plus keepalives
     * until something changes. So the stream is a delta feed, and the collection has to be read
     * once over REST before the deltas mean anything.
     */
    override fun setVisible(visibilityState: ProviderVisibilityState) {
        if (!visibilityState.providerVisible) {
            stopWatching()
            return
        }
        refresh()
        startWatching()
    }

    /**
     * The live subscription, and the coalescing consumer behind it. Null when not watching.
     *
     * Two jobs rather than one because they fail differently: the stream reconnects on its own
     * forever, while the consumer only ever ends when cancelled. Cancelling the parent handle
     * takes both down, which is what [stopWatching] does.
     */
    private var watchJob: Job? = null

    /**
     * Changes waiting to be turned into a refresh, conflated to exactly one.
     *
     * Conflated because **XO is chatty out of proportion to what changed**: measured 2026-08-21, a
     * clean shutdown followed by a start produced *ten* `update` frames for two transitions, and
     * taking a snapshot pushes further updates onto the parent VM as its `snapshots` array and
     * `current_operations` move. Refreshing per frame would be worse than the polling this
     * replaces. The conflated channel plus the settle delay below collapses a burst into one read.
     */
    private val pendingChanges = Channel<Unit>(Channel.CONFLATED)

    /**
     * How long to wait for a burst to finish before re-reading the pool.
     *
     * A second is comfortably longer than the gaps inside a burst and short enough that a user
     * watching a VM boot sees the row move while they are still looking at it. It also bounds the
     * cost: at most one pool read per second however hard the appliance pushes.
     */
    private val settleMillis = 1_000L

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        if (!settings.isConfigured) return
        val stream = XoEventStream(
            baseUrl = settings.baseUrl!!,
            token = settings.token ?: return,
            allowUnauthorized = settings.allowUnauthorized,
        )
        watchJob = scope.launch {
            launch {
                // A trailing refresh per burst. The channel is conflated, so everything that
                // arrives during the settle window collapses into the single pending item that
                // drives the next pass.
                for (unused in pendingChanges) {
                    delay(settleMillis)
                    // Quiet: the list is already on screen and already correct. See reload.
                    reload(announce = false)
                }
            }
            stream
                .changes(
                    onDisconnect = { cause ->
                        // Logged at info rather than error, and deliberately: a dropped stream is
                        // an ordinary event on a connection meant to live for hours: a pool
                        // reboots, a laptop suspends, a VPN drops. It is not a plugin fault and
                        // the reconnect is automatic. What matters is that it is visible at all,
                        // because a silently dead stream and a genuinely quiet pool look identical
                        // from the environment list.
                        if (cause == null) {
                            logger.info("XCP-ng: event stream closed by ${settings.baseUrl}, reconnecting.")
                        } else {
                            logger.info(
                                "XCP-ng: event stream to ${settings.baseUrl} dropped " +
                                    "(${cause::class.simpleName}: ${cause.message}), reconnecting.",
                            )
                        }
                    },
                )
                .collect { change ->
                    // Anything that is not a VM is ignored rather than filtered at subscription
                    // time as well, because a frame does not name its subscription: the only
                    // discriminator is `type` on the payload. With one subscription this is
                    // belt and braces; it stops being that the moment a second one is added.
                    if (change.collection != VM_COLLECTION) return@collect
                    // Deliberately not logged per frame. Ten frames for one shutdown would bury
                    // the lines that matter, and the refresh this triggers logs the VM count.
                    pendingChanges.trySend(Unit)
                }
        }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    /** A refresh the user asked for, one way or another. Announces itself as loading. */
    override fun refresh() = reload(announce = true)

    /**
     * Re-read the pool.
     *
     * [announce] publishes [LoadableState.Loading] first, which is right for a refresh the user
     * triggered (opening the page, saving settings), and wrong for one an event triggered.
     * Before the event stream, every refresh was the first kind, so this did not need a
     * distinction and did not have one.
     *
     * It does now. A booting VM produces a burst of frames, each settling into a re-read, and
     * announcing every one of those would flip a list that is already on screen and already
     * correct back to loading roughly once a second. The user would see the pool flicker while
     * watching a VM they just started, which reads as the plugin struggling rather than as it
     * working. A background re-read has nothing to announce: the list stays, and the rows change
     * when the answer arrives.
     */
    private fun reload(announce: Boolean) {
        if (!settings.isConfigured) {
            logger.info("XCP-ng: not configured, no baseUrl or token. Skipping refresh.")
            environmentList.value = LoadableState.Value(emptyList())
            return
        }
        if (settings.tokenIsPlaintext) {
            // Say it out loud rather than silently tolerating it. See XoSettings.token.
            logger.warn("XCP-ng: token read from plaintext settings, not the keychain.")
        }
        scope.launch {
            if (announce) {
                // Loading is a raw-typed singleton in the Java-facing API, hence the cast.
                @Suppress("UNCHECKED_CAST")
                environmentList.value =
                    LoadableState.Loading as LoadableState<List<RemoteProviderEnvironment>>
            }
            val result = runCatching { newClient().use { it.listVms() } }
            result.onSuccess { vms ->
                logger.info("XCP-ng: ${vms.size} VMs from ${settings.baseUrl}")
                // Update in place where the VM is already known, construct only what is new, and
                // drop what the pool no longer has. See environmentsByUuid.
                val environments = vms.map { vm ->
                    environmentsByUuid.getOrPut(vm.uuid) {
                        XcpngVmEnvironment(
                            vm, i18n, logger, ui, uiComponents, statePalette, settings, scope, ::newClient,
                        )
                    }.also { it.update(vm) }
                }
                environmentsByUuid.keys.retainAll(vms.mapTo(mutableSetOf()) { it.uuid })
                environmentList.value = LoadableState.Value(environments)
            }.onFailure { e ->
                logger.error(e, "XCP-ng: could not list VMs from ${settings.baseUrl}")
                // Only a refresh the user asked for is allowed to empty the list. A quiet one
                // failing means a re-read triggered by an event did not land (a blip, a pool
                // restarting, a laptop waking), and blanking a correct list over that would turn
                // every transient failure into a pool that appears to have lost all its VMs.
                // Events make this reachable in a way it was not before: refreshes used to happen
                // at most once per appearance, and now they happen whenever XO pushes.
                if (announce) {
                    environmentList.value = LoadableState.Value(emptyList())
                }
            }
        }
    }

    /**
     * One page instance, reused.
     *
     * Rebuilding it per call would discard whatever the user had half-typed the moment Toolbox
     * asked for the page again, and [getOverrideUiPage] is called on every visibility change.
     */
    private val settingsPage by lazy {
        PoolSettingsPage(
            settings,
            i18n,
            showProblem = { problem ->
                scope.launch {
                    ui.showInfoPopup(i18n.ptrl("Not saved"), i18n.pnotr(problem), i18n.ptrl("OK"))
                }
            },
            onSaved = {
                logger.info("XCP-ng: settings saved.")
                // The environment list is empty while unconfigured, so nothing would appear until
                // the next visibility change without this.
                refresh()
                // The stream holds the URL, token and TLS policy it was constructed with, so a
                // save has to replace it rather than leave it talking to the old appliance. Not
                // restarting here is the failure where the pool listing recovers on Save and the
                // pushes silently keep going to the previous host.
                stopWatching()
                startWatching()
            },
            testConnection = ::testConnection,
        )
    }

    /**
     * One read against the appliance, reported in a popup.
     *
     * Built from the form's own values rather than from [settings], so it answers the question
     * actually being asked ("does what I just typed work"), and writes nothing whatever the
     * answer is.
     *
     * [XoClient.ping] is the only blocking call this plugin has, and this is what the comment on
     * it always claimed it was for. `Dispatchers.IO` because it is blocking, and a fresh client
     * per attempt because the values under test are not the stored ones.
     */
    private fun testConnection(attempt: PoolSettingsPage.Attempt) {
        scope.launch {
            // FIRST statement in the lambda, before the token is even resolved, and that position
            // is the whole value of it rather than a detail.
            //
            // It started one branch lower, after the no-token check. That made it a record of what
            // a test *compared*, and it could not answer the question actually asked of it: "I
            // pressed the button and nothing happened". A line that only proves the code ran once
            // it has decided to run cannot distinguish "never invoked" from "returned early", and
            // that is the same trap getEnvironmentIssueFlow() cost an evening to: establish that
            // your code runs at all before reasoning about what it produced.
            //
            // So: no line means the button was not pressed. Every other outcome logs or pops.
            //
            // Kept after doing its job, because what it did was disprove a defect rather than find
            // one. matchesStored was true and the complained-of message had come from a different
            // click. A test is user-initiated and rare, so one INFO line costs nothing.
            //
            // Never logs the token, only whether one was typed.
            logger.info(
                "XCP-ng: test attempt url=<${attempt.baseUrl}> stored=<${settings.baseUrl}> " +
                    "typedToken=${attempt.typedToken != null} " +
                    "insecure=${attempt.allowUnauthorized} storedInsecure=${settings.allowUnauthorized} " +
                    "matchesStored=${attempt.matchesStored(settings.baseUrl, settings.allowUnauthorized)}",
            )
            // Blank field plus nothing stored is already refused by the form; this branch is for
            // a token that vanished between the check and the click, and it beats a `!!`.
            val token = attempt.typedToken ?: settings.token
            if (token == null) {
                logger.warn("XCP-ng: test attempt had no token to use.")
                ui.showInfoPopup(
                    i18n.ptrl("Not tested"),
                    i18n.pnotr("There is no token to test with. Type one and try again."),
                    i18n.ptrl("OK"),
                )
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    XoRestClient(
                        baseUrl = attempt.baseUrl,
                        token = token,
                        allowUnauthorized = attempt.allowUnauthorized,
                    ).use { it.ping() }
                }
            }
            val failure = outcome.exceptionOrNull()
            if (failure == null) {
                logger.info("XCP-ng: test connection to ${attempt.baseUrl} succeeded.")
                // The second sentence depends on whether anything was actually edited. See
                // Attempt.matchesStored for why: "nothing was saved" is the point when it is an
                // edit and a warning about imaginary work when it is not.
                val tail = if (attempt.matchesStored(settings.baseUrl, settings.allowUnauthorized)) {
                    "These are the settings already in use, and nothing needed changing."
                } else {
                    "Nothing was saved. Open Settings again and press Save to keep these values."
                }
                ui.showInfoPopup(
                    i18n.ptrl("Connected"),
                    i18n.pnotr("${attempt.baseUrl} answered and accepted the token. $tail"),
                    i18n.ptrl("OK"),
                )
            } else {
                // Logged at warn rather than error: a failed test is this button working, not the
                // plugin failing. The stack trace is kept because a branch of
                // xoUnreachableMessage that never fires is one nobody would otherwise notice.
                logger.warn(failure, "XCP-ng: test connection to ${attempt.baseUrl} failed.")
                // Which settings failed is as useful as why, and the two cases need opposite
                // actions. Learned the hard way on 2026-08-21: a failing value was tested, then
                // saved, and the pool stopped listing: the log went straight from "11 VMs" to
                // SSLHandshakeException one millisecond after "settings saved".
                val whose = if (attempt.matchesStored(settings.baseUrl, settings.allowUnauthorized)) {
                    "These are the settings the pool is actually using, so it is not listing " +
                        "either. This is not just a bad edit."
                } else {
                    "Nothing was changed. The pool is still on its saved settings, and pressing " +
                        "Save would replace them with the ones that just failed."
                }
                ui.showInfoPopup(
                    i18n.ptrl("Could not connect"),
                    i18n.pnotr("${xoUnreachableMessage(attempt.baseUrl, failure)} $whose"),
                    i18n.ptrl("OK"),
                )
            }
        }
    }

    /**
     * Shown *instead of* the environment list, or null to let the list render.
     *
     * This is the hook that turns a fresh install from "an empty list plus an instruction to go
     * and edit a JSON file" into a form. Coder uses the same mechanism for its sign-in wizard.
     */
    override fun getOverrideUiPage(): UiPage? =
        if (settings.isConfigured) null else settingsPage

    /** The provider's own menu, beside the environment list rather than on a VM. */
    override val additionalPluginActions: StateFlow<List<ActionDescription>> = MutableStateFlow(
        listOf(
            object : RunnableActionDescription {
                override val label: LocalizableString = i18n.ptrl("Settings")
                override fun run() {
                    ui.showUiPage(settingsPage)
                }
            },
        ),
    )

    /** For a `jetbrains://` deep link. Not supported yet. */
    override suspend fun handleUri(uri: URI) {
        // TODO
    }

    override fun close() {
        // The REST client is created per refresh and closed by `use`, so there is nothing of that
        // kind held here. The event stream is the exception: it is a socket held open across
        // calls, and it is exactly what the issue warned would leak if it outlived the provider.
        stopWatching()
        pendingChanges.close()
    }
}
