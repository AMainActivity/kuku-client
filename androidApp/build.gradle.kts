plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.messenger.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.messenger.kmp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = providers.gradleProperty("appVersionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.2").get()
    }
    val signingValues=listOf("RELEASE_STORE_FILE","RELEASE_STORE_PASSWORD","RELEASE_KEY_ALIAS","RELEASE_KEY_PASSWORD")
        .map { providers.environmentVariable(it).orNull }
    require(signingValues.all {it==null} || signingValues.all {!it.isNullOrBlank()}) {
        "Release signing requires all four RELEASE_* environment variables."
    }
    if(signingValues.all {it!=null}) {
        signingConfigs.create("production") {
            storeFile=file(requireNotNull(signingValues[0]))
            storePassword=signingValues[1]
            keyAlias=signingValues[2]
            keyPassword=signingValues[3]
        }
    }
    buildTypes {
        release {
            isDebuggable=false
            isMinifyEnabled=true
            isShrinkResources=true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
            signingConfigs.findByName("production")?.let {signingConfig=it}
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }
}
dependencies {
    implementation(project(":shared-ui"))
    implementation(libs.activity.compose)
}

