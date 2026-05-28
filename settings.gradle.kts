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

include(":apps:assistant")
include(":apps:calendar")
include(":apps:documents")
include(":apps:notes")
include(":apps:pitch")
include(":apps:recorder")
include(":apps:scores")
include(":apps:trainer")
