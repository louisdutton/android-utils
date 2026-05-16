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

rootProject.name = "grapheneos-essentials"

include(":core:locations")

include(":apps:agent")
include(":apps:maps")
include(":apps:messaging")
include(":apps:calendar")
