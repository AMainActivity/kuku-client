plugins {
    id("messenger.compose")
}
kotlin {
    android {
        namespace = "dev.messenger.core.ui"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}
compose.resources { publicResClass = true; packageOfResClass = "dev.messenger.ui.resources" }

