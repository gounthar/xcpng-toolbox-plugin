package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.PluginSecretStore
import com.jetbrains.toolbox.api.core.PluginSettingsStore
import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.core.diagnostics.Logger
import com.jetbrains.toolbox.api.core.util.LoadableState
import com.jetbrains.toolbox.api.localization.LocalizableStringFactory
import com.jetbrains.toolbox.api.remoteDev.ProviderVisibilityState
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider
import com.jetbrains.toolbox.api.remoteDev.RemoteProviderEnvironment
import com.jetbrains.toolbox.api.remoteDev.states.EnvironmentStateColorPalette
import com.jetbrains.toolbox.api.ui.ToolboxUi
import com.jetbrains.toolbox.api.ui.components.UiComponents
import dev.gounthar.xcpng.toolbox.xo.XoRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URI

/** Lists the VMs on a pool as Toolbox environments. */
class XcpngRemoteProvider(
    serviceLocator: ServiceLocator,
) : RemoteProvider("XCP-ng") {

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
            "Set baseUrl and token in this plugin's settings.json, then reopen this page."
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
                        XcpngVmEnvironment(vm, i18n, logger, ui, uiComponents, statePalette, scope, ::newClient)
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

    /** For a `jetbrains://` deep link. Not supported yet. */
    override suspend fun handleUri(uri: URI) {
        // TODO
    }

    override fun close() {
        // The client is created per refresh and closed by `use`, so there is nothing held here.
    }
}
