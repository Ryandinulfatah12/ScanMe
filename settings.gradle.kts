pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "ScanMe"

include(":app")
include(":core:camera")
include(":core:imaging")
include(":core:ml")
include(":core:domain")
include(":core:data")
include(":core:ui")
include(":feature:scanner")
include(":feature:editor")
include(":feature:documents")
include(":feature:debug")
