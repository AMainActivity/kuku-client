plugins {
    id("messenger.compose")
}
kotlin {
    android {
        namespace = "dev.messenger.feature.auth"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(libs.navigation.event)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.viewmodel)
            implementation(project(":core-ui"))
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        getByName("androidHostTest").dependencies {
            implementation(project(":shared-data"))
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}


