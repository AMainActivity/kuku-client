plugins { `kotlin-dsl` }
kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }
dependencies {
    implementation("org.jetbrains.compose:compose-gradle-plugin:${libs.versions.compose.get()}")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}
gradlePlugin {
    plugins {
        register("messengerCompose") {
            id = "messenger.compose"
            implementationClass = "MessengerComposePlugin"
        }
        register("messengerKmp") {
            id = "messenger.kmp"
            implementationClass = "MessengerKmpPlugin"
        }
    }
}
