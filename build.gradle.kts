plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly, not implementation: Toolbox provides these at runtime.
    // Bundling them causes classloader conflicts, the same trap Red Hat's
    // Gateway plugin documents for kotlinx-coroutines. Coder's packaging
    // enforces it by failing the build if a provided library reaches the zip;
    // the list is kotlin, remote-dev-api, core-api, ui-api, annotations,
    // localization-api, slf4j-api.
    compileOnly(libs.bundles.toolbox.plugin.api)
    compileOnly(libs.coroutines.core)
    compileOnly(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.toolbox.plugin.api)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Toolbox packaging.
//
// There is no plugin.xml and no IntelliJ Gradle plugin: Toolbox packaging is
// hand-rolled per vendor. This is a deliberately minimal version of what
// coder/coder-jetbrains-toolbox does in its buildSrc convention plugin. It is
// kept inline rather than in buildSrc because one project does not need a
// convention plugin, and it writes extension.json as plain text rather than
// pulling in org.jetbrains.intellij.plugins:structure-toolbox for six fields.
// ---------------------------------------------------------------------------

/** extensionId is project.group. It names the install directory and the marketplace entry. */
val extensionId = project.group.toString()
val extensionVersion = project.version.toString()
val toolboxApiVersion = libs.versions.toolbox.plugin.api.get()

val extensionJson = layout.buildDirectory.file("generated/extension.json")

val generateExtensionJson by tasks.registering {
    description = "Writes the Toolbox extension.json manifest."
    val output = extensionJson
    val id = extensionId
    val version = extensionVersion
    val apiVersion = toolboxApiVersion
    inputs.property("id", id)
    inputs.property("version", version)
    inputs.property("apiVersion", apiVersion)
    outputs.file(output)
    // Schema read out of platform-plugins.jar on 2026-08-19, after Toolbox rejected a
    // hand-written manifest with:
    //   MissingFieldException: Field 'readableName' is required for type
    //   'com.jetbrains.toolbox.platform.plugins.Meta', but it was missing
    // ExtensionJson: id, version, meta, apiVersion, icon (icon optional).
    // Meta:          readableName, description, vendor, url (+ optional colour fields).
    // Note it is readableName, NOT name. Coder does not hit this because
    // structure-toolbox's ToolboxMeta maps its `name` parameter onto readableName.
    //
    // readableName names the PLUGIN. It is not the string in the environment list — that comes
    // from PROVIDER_NAME in XcpngRemoteProvider.kt, and the two must be changed together. PR #6
    // changed only this one and the list still read "XCP-ng" on the next launch.
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "id": "$id",
              "version": "$version",
              "apiVersion": "$apiVersion",
              "meta": {
                "readableName": "XCP-ng (unofficial)",
                "description": "Open a JetBrains IDE against a VM on an XCP-ng pool.",
                "vendor": "gounthar",
                "url": "https://github.com/gounthar/xcpng-toolbox-plugin"
              }
            }
            """.trimIndent() + "\n"
        )
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(extensionId)
    from(generateExtensionJson)
}

/**
 * Where Toolbox looks for plugins, from Coder's `PluginUtils.getPluginInstallDir`.
 * Override with `-PtoolboxPluginsDir=...` or a line in `gradle.properties`.
 */
val toolboxPluginsDir: Provider<String> = providers.gradleProperty("toolboxPluginsDir")
    .orElse(
        providers.provider {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val home = System.getProperty("user.home")
            val isWsl = System.getenv("WSL_DISTRO_NAME") != null ||
                runCatching { File("/proc/version").readText() }.getOrDefault("")
                    .contains("microsoft", ignoreCase = true)
            when {
                // Deliberately refuses to guess. Under WSL the Linux path resolves
                // happily and Toolbox running on the Windows side never reads it, so
                // installPlugin would report success and change nothing visible. That
                // failure costs an afternoon; an error costs a minute.
                isWsl -> null
                // NOTE the `cache` segment on Windows. Toolbox scans
                // WellKnownPaths.getToolboxCacheLocation().resolve("plugins"), and on Windows
                // ToolboxCacheLocation is ToolboxDataLocation/cache. Installing into
                // .../Toolbox/plugins instead (which is PluginDataPath, where plugins keep
                // their settings) loads nothing and logs "No external plugins found".
                // Verified against Toolbox 3.7.0.87111 by disassembling PluginManagerImpl.
                osName.contains("win") ->
                    "${System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"}/JetBrains/Toolbox/cache/plugins"
                // All three now match JetBrains' own documentation, which lists exactly
                // these paths:
                // https://www.jetbrains.com/help/toolbox-app/plugin-packaging.html
                // macOS Library/Caches is already a cache root, hence no extra segment;
                // Linux uses XDG_CACHE_HOME, not XDG_DATA_HOME. Only the Windows branch has
                // actually been run here.
                osName.contains("mac") || osName.contains("darwin") ->
                    "$home/Library/Caches/JetBrains/Toolbox/plugins"
                else ->
                    "${System.getenv("XDG_CACHE_HOME") ?: "$home/.cache"}/JetBrains/Toolbox/plugins"
            }
        }
    )

val installPlugin by tasks.registering(Sync::class) {
    description = "Copies the plugin into the local Toolbox plugins directory."
    val resolvedDir = toolboxPluginsDir.orNull
    val id = extensionId
    from(tasks.named<Jar>("jar"))
    from(generateExtensionJson)
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("icon.svg", "pluginIcon.svg")
    }
    // Nothing else is bundled: every Toolbox API is compileOnly, so there are no
    // runtime dependencies to filter. Revisit if a real dependency is added.
    if (resolvedDir != null) {
        into("$resolvedDir/$id")
    } else {
        // Fail in doFirst rather than while resolving the destination, so the
        // message is the error rather than a configuration-cache stack trace.
        into(layout.buildDirectory.dir("install-not-configured"))
        doFirst {
            error(
                buildString {
                    appendLine("Cannot determine the Toolbox plugins directory.")
                    appendLine()
                    appendLine("This looks like WSL. The Linux path resolves fine and Toolbox running")
                    appendLine("on the Windows side never reads it, so installing there would report")
                    appendLine("success and change nothing. Point at the Windows location instead:")
                    appendLine()
                    appendLine("  ./gradlew installPlugin \\")
                    appendLine("    -PtoolboxPluginsDir=/mnt/c/Users/<you>/AppData/Local/JetBrains/Toolbox/cache/plugins")
                    appendLine()
                    append("or set toolboxPluginsDir=... in gradle.properties.")
                }
            )
        }
    }
}

val packagePlugin by tasks.registering(Zip::class) {
    description = "Builds the distributable Toolbox plugin zip."
    archiveBaseName.set(extensionId)
    from(tasks.named<Jar>("jar"))
    from(generateExtensionJson)
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("icon.svg", "pluginIcon.svg")
    }
}
