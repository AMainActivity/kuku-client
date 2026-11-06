import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared targets, JVM toolchain, Android resources and host tests for every KMP module. */
class MessengerKmpPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        val moduleName=name
        val allowedProjects=when {
            moduleName.startsWith("feature-") -> setOf(":shared-domain",":core-ui")
            moduleName=="core-ui" -> setOf(":shared-domain")
            moduleName=="shared-domain" -> emptySet()
            moduleName=="shared-data" -> setOf(":shared-domain")
            moduleName=="core-runtime" -> setOf(":shared-domain",":shared-data")
            moduleName=="shared-ui" -> null // Composition root wires all features and infrastructure.
            else -> error("Declare an architecture policy for KMP module $moduleName")
        }
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KotlinMultiplatformExtension> {
            // Validate declarations in production source sets, not plugin-internal metadata
            // configurations (SwiftPM lock-file aggregation can reference every project).
            sourceSets.configureEach {
                if (!name.contains("test", ignoreCase = true)) {
                    listOf(apiConfigurationName, implementationConfigurationName,
                        compileOnlyConfigurationName, runtimeOnlyConfigurationName).forEach { configurationName ->
                        project.configurations.named(configurationName).configure {
                            dependencies.all {
                                if (this is ProjectDependency && allowedProjects != null) {
                                    require(path in allowedProjects) {
                                        "Module ${project.path} must not depend on $path in $configurationName; allowed: $allowedProjects"
                                    }
                                }
                                if (moduleName.startsWith("feature-") || moduleName == "core-ui" || moduleName == "shared-domain") {
                                    require(group !in setOf("io.ktor", "androidx.room", "androidx.sqlite")) {
                                        "Infrastructure dependency $group:$name belongs in shared-data ($configurationName)"
                                    }
                                }
                                if (moduleName == "shared-domain") {
                                    require(group?.contains("compose") != true && group != "io.insert-koin" &&
                                        group != "androidx.lifecycle" && group != "org.jetbrains.androidx.lifecycle") {
                                        "Domain must not depend on UI or DI frameworks: $group:$name ($configurationName)"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            iosArm64()
            iosSimulatorArm64()
            jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
            extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
                compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
                minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
                androidResources.enable = true
                withHostTest { isIncludeAndroidResources = true }
            }
        }
    }
}
