rootProject.name = "xcpng-toolbox"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The Toolbox plugin API is not on Central.
        maven("https://packages.jetbrains.team/maven/p/tbx/toolbox-api")
    }
}
