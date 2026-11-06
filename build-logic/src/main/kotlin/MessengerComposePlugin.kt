import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Only UI libraries share these dependencies; domain/data remain free of Compose. */
class MessengerComposePlugin:Plugin<Project> {
    override fun apply(project:Project) = with(project) {
        pluginManager.apply("messenger.kmp")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val libs=extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                listOf("coroutines-core","lifecycle-viewmodel","lifecycle-compose",
                    "compose-runtime","compose-foundation","compose-resources","material3").forEach {
                    implementation(libs.findLibrary(it).get())
                }
            }
            sourceSets.getByName("androidMain").dependencies {
                implementation(libs.findLibrary("activity-compose").get())
            }
        }
    }
}
