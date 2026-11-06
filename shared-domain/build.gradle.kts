plugins {
    id("messenger.kmp")
}
kotlin {
    android {
        namespace = "dev.messenger.domain"
    }
    sourceSets {
        commonMain.dependencies { api(libs.coroutines.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

