pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MessengerKMP"
include(":androidApp", ":shared-domain", ":shared-data", ":shared-ui")
include(":core-ui", ":core-runtime", ":feature-auth", ":feature-chats", ":feature-conversation", ":feature-profile", ":feature-groups", ":feature-admin")
