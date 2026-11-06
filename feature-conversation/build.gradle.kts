plugins {
    id("messenger.compose")
}
kotlin {
    android {
        namespace = "dev.messenger.feature.conversation"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.viewmodel)
            implementation(project(":core-ui"))
        }
        androidMain.dependencies {
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
            implementation(libs.camera.video)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.coil.compose)
            implementation(libs.coil.video)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(project(":shared-data"))
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}


