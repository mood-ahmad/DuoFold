pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DuoFold"

include(":app")
include(":motion")
include(":backdrop")
include(":miuix:miuix-core")
include(":miuix:miuix-squircle")
include(":miuix:miuix-shader")
include(":miuix:miuix-ui")
include(":miuix:miuix-preference")
include(":miuix:miuix-blur")
include(":miuix:miuix-navigation3-ui")