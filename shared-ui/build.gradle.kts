plugins {
    id("messenger.compose")
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    android {
        namespace = "dev.messenger.ui"
    }
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "MessengerKit"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(project(":shared-data"))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.viewmodel)
            implementation(libs.serialization.json)
            implementation(libs.navigation3)
            implementation(libs.lifecycle.navigation3)
            implementation(project(":core-ui"))
            implementation(project(":core-runtime"))
            implementation(project(":feature-auth"))
            implementation(project(":feature-chats"))
            implementation(project(":feature-conversation"))
            implementation(project(":feature-profile"))
            implementation(project(":feature-groups"))
            implementation(project(":feature-admin"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(libs.coroutines.test)
            implementation(libs.ktor.core)
            implementation(libs.ktor.mock)
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}


