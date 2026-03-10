plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read baseApiUrl from (in order): project property `baseApiUrl`, gradle.properties, then local.properties
fun readSimpleProp(file: java.io.File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines().map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
}

val baseApiUrlProp: String? = (project.findProperty("baseApiUrl") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "baseApiUrl")
    ?: readSimpleProp(rootProject.file("local.properties"), "baseApiUrl")

val googleAdsEnabledProp: String = (project.findProperty("googleAdsEnabled") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "googleAdsEnabled")
    ?: readSimpleProp(rootProject.file("local.properties"), "googleAdsEnabled")
    ?: "true"

val facebookAdsEnabledProp: String = (project.findProperty("facebookAdsEnabled") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "facebookAdsEnabled")
    ?: readSimpleProp(rootProject.file("local.properties"), "facebookAdsEnabled")
    ?: "true"

val webUrlProp: String = (project.findProperty("webUrl") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "webUrl")
    ?: readSimpleProp(rootProject.file("local.properties"), "webUrl")
    ?: ""

android {
    namespace = "jmotley.com.jspades"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "jmotley.com.jspades"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_API_URL",
            "\"https://p0pr4ffvff.execute-api.us-east-1.amazonaws.com/prod\"")

        // Provide BASE_API_URL to the Android app via BuildConfig. Falls back to production API.
        val resolvedBaseApi = baseApiUrlProp ?: "https://p0pr4ffvff.execute-api.us-east-1.amazonaws.com/prod"
        buildConfigField("String", "BASE_API_URL", "\"${resolvedBaseApi}\"")
        buildConfigField("Boolean", "GOOGLE_ADS_ENABLED", googleAdsEnabledProp)
        buildConfigField("Boolean", "FACEBOOK_ADS_ENABLED", facebookAdsEnabledProp)
        buildConfigField("String", "WEB_URL", "\"${webUrlProp}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.ads)
    implementation(libs.facebook.audience.network)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}