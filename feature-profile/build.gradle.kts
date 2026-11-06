plugins {
    id("messenger.compose")
}
kotlin {
    android {
        namespace = "dev.messenger.feature.profile"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.viewmodel)
            implementation(project(":core-ui"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}


