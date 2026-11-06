plugins {
    id("messenger.kmp")

}
kotlin {
    android {
        namespace = "dev.messenger.core.runtime"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(project(":shared-data"))
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies { implementation(libs.android.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit)
            implementation(libs.serialization.json)
        }
    }
}


