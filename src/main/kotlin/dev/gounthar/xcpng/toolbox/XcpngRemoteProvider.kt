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
import dev.gounthar.xcpng.toolbox.xo.XoRestClient
import dev.gounthar.xcpng.toolbox.xo.xoUnreachableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
     * Toolbox tells us when the user is actually looking, and the refresh hangs off that on
     * purpose: polling every configured pool in the background is exactly the behaviour an
     * operator would notice and dislike.
     */
    override fun setVisible(visibilityState: ProviderVisibilityState) {
        if (!visibilityState.providerVisible) return
        refresh()
    }

    override fun refresh() {
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
            // Loading is a raw-typed singleton in the Java-facing API, hence the cast.
            @Suppress("UNCHECKED_CAST")
            environmentList.value =
                LoadableState.Loading as LoadableState<List<RemoteProviderEnvironment>>
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
                environmentList.value = LoadableState.Value(emptyList())
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
            },
            testConnection = ::testConnection,
        )
    }

    /**
     * One read against the appliance, reported in a popup.
     *
     * Built from the form's own values rather than from [settings], so it answers the question
     * actually being asked — "does what I just typed work" — and writes nothing whatever the
     * answer is.
     *
     * [XoClient.ping] is the only blocking call this plugin has, and this is what the comment on
     * it always claimed it was for. `Dispatchers.IO` because it is blocking, and a fresh client
     * per attempt because the values under test are not the stored ones.
     */
    private fun testConnection(attempt: PoolSettingsPage.Attempt) {
        scope.launch {
            // Blank field plus nothing stored is already refused by the form; this branch is for
            // a token that vanished between the check and the click, and it beats a `!!`.
            val token = attempt.typedToken ?: settings.token
            if (token == null) {
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
                ui.showInfoPopup(
                    i18n.ptrl("Connected"),
                    i18n.pnotr(
                        "${attempt.baseUrl} answered and accepted the token. Nothing was saved " +
                            "— open Settings again and press Save to keep these values.",
                    ),
                    i18n.ptrl("OK"),
                )
            } else {
                // Logged at warn rather than error: a failed test is this button working, not the
                // plugin failing. The stack trace is kept because a branch of
                // xoUnreachableMessage that never fires is one nobody would otherwise notice.
                logger.warn(failure, "XCP-ng: test connection to ${attempt.baseUrl} failed.")
                ui.showInfoPopup(
                    i18n.ptrl("Could not connect"),
                    i18n.pnotr(xoUnreachableMessage(attempt.baseUrl, failure)),
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
        // The client is created per refresh and closed by `use`, so there is nothing held here.
    }
}
