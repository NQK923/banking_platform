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

rootProject.name = "banking-platform"

include(
    "common-domain",
    "api-gateway",
    "account-service",
    "transaction-service",
    "notification-service",
    "audit-service",
    "reconciliation-service",
    "proto"
)
