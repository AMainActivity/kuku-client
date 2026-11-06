plugins {
    id("messenger.compose")
    alias(libs.plugins.aboutlibraries)
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



// Package license metadata from the whole application dependency graph for offline use.
val licenseResources = layout.buildDirectory.dir("generated/licenseResources")
aboutLibraries {
    offlineMode.set(true)
    collect.configPath.set(layout.projectDirectory.dir("license-config"))
    export.outputFile.set(licenseResources.map { it.file("files/aboutlibraries.json") })
    collect.includeTestVariants.set(false)
    collect.fetchRemoteLicense.set(false)
    collect.fetchRemoteFunding.set(false)
}
compose.resources {
    packageOfResClass = "dev.messenger.app.resources"
    customDirectory(sourceSetName = "commonMain", directoryProvider = tasks.named("exportLibraryDefinitions").map { licenseResources.get() })
}

tasks.named("exportLibraryDefinitions").configure {
    doLast {
        val report = groovy.json.JsonSlurper().parse(licenseResources.get().file("files/aboutlibraries.json").asFile) as Map<*, *>
        val libraries = report["libraries"] as List<*>
        val licenses = report["licenses"] as Map<*, *>
        check(libraries.isNotEmpty()) { "The application license report is empty" }
        libraries.forEach { item ->
            val library = item as Map<*, *>
            val ids = library["licenses"] as? List<*> ?: emptyList<Any>()
            check(ids.isNotEmpty() && ids.all { id ->
                ((licenses[id] as? Map<*, *>)?.get("content") as? String)?.isNotBlank() == true
            }) { "Missing bundled license text for ${library["uniqueId"]}; add it to shared-ui/license-config/licenses" }
        }
    }
}
