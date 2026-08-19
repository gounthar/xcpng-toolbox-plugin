package dev.gounthar.xcpng.toolbox

import com.jetbrains.toolbox.api.core.ServiceLocator
import com.jetbrains.toolbox.api.remoteDev.RemoteDevExtension
import com.jetbrains.toolbox.api.remoteDev.RemoteProvider

/**
 * Entry point. Toolbox finds this through a `META-INF/services` file naming
 * [RemoteDevExtension]; there is no plugin.xml and no extension-point registration.
 */
class XcpngToolboxExtension : RemoteDevExtension {
    override fun createRemoteProviderPluginInstance(serviceLocator: ServiceLocator): RemoteProvider =
        XcpngRemoteProvider(serviceLocator)
}
