// Gradle reads gradle/libs.versions.toml automatically as the `libs` catalog.

rootProject.name = "pretium"

dependencyResolutionManagement {
    // Modules may not declare their own repositories: one declaration here
    // means every dependency is resolved from a known set.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// events    — the messages that cross Kafka, shared by both services
// collector — fetches sources, keeps the raw evidence, extracts observations
// catalog   — validates, stores, reconciles and serves the comparison API
include("events", "collector", "catalog")
