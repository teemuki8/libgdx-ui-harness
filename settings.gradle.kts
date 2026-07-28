import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "libgdx-ui-harness"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "harness-core",
    "harness-scene2d",
    "harness-lwjgl3",
    "harness-protocol",
    "harness-mcp",
    "harness-fixtures",
    "benchmarks",
)
