plugins {
    id("messenger.kmp")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}
kotlin {
    android {
        namespace = "dev.messenger.data"
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.serialization.json)
            implementation(libs.ktor.core)
            implementation(libs.okio)
            implementation(libs.ktor.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies { implementation(libs.ktor.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
room { schemaDirectory("$projectDir/schemas") }
configurations.matching { it.name.contains("AndroidHostTest", ignoreCase = true) }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}"))
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    inputs.property("liveProbeHost", providers.environmentVariable("KUKU_LIVE_PROBE_HOST").orElse(""))
    val includeContractTests = providers.gradleProperty("includeContractTests")
        .map { it.toBooleanStrict() }.getOrElse(true)
    inputs.property("includeContractTests", includeContractTests)
    if (includeContractTests) {
        val contractDirectory = rootProject.file(providers.gradleProperty("contractDirectory").getOrElse("contracts/v0.1.0"))
        inputs.dir(contractDirectory).withPropertyName("publishedContractDirectory")
        systemProperty("messenger.contractDirectory", contractDirectory.absolutePath)
    } else {
        filter { excludeTestsMatching("dev.messenger.data.wire.PublishedContractTest") }
    }
    systemProperty("messenger.schemaDirectory", project.file("schemas").absolutePath)
}
