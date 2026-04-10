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

val rewardedTestModeProp: String = (project.findProperty("rewardedTestMode") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "rewardedTestMode")
    ?: readSimpleProp(rootProject.file("local.properties"), "rewardedTestMode")
    ?: "false"

val googleAdsEnabledProp: String = (project.findProperty("googleAdsEnabled") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "googleAdsEnabled")
    ?: readSimpleProp(rootProject.file("local.properties"), "googleAdsEnabled")
    ?: "true"

val facebookAdsEnabledProp: String = (project.findProperty("facebookAdsEnabled") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "facebookAdsEnabled")
    ?: readSimpleProp(rootProject.file("local.properties"), "facebookAdsEnabled")
    ?: "true"

val unityAdsEnabledProp: String = (project.findProperty("unityAdsEnabled") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "unityAdsEnabled")
    ?: readSimpleProp(rootProject.file("local.properties"), "unityAdsEnabled")
    ?: "true"

val unityGameIdProp: String = (project.findProperty("unityGameId") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "unityGameId")
    ?: readSimpleProp(rootProject.file("local.properties"), "unityGameId")
    ?: ""

val levelPlayAppKeyProp: String = (project.findProperty("levelPlayAppKey") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayAppKey")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayAppKey")
    ?: ""

val levelPlayInterstitialAdUnitIdProp: String = (project.findProperty("levelPlayInterstitialAdUnitId") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayInterstitialAdUnitId")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayInterstitialAdUnitId")
    ?: ""

val levelPlayRewardedUndoLastTrickProp: String = (project.findProperty("levelPlayRewardedundoLastTrick") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedundoLastTrick")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedundoLastTrick")
    ?: ""

val levelPlayRewardedPeekCardProp: String = (project.findProperty("levelPlayRewardedpeekCard") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedpeekCard")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedpeekCard")
    ?: ""

val levelPlayRewardedExtraBookProp: String = (project.findProperty("levelPlayRewardedextraBook") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedextraBook")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedextraBook")
    ?: ""

val levelPlayRewardedReplayHandProp: String = (project.findProperty("levelPlayRewardedreplayHand") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedreplayHand")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedreplayHand")
    ?: ""

val levelPlayRewardedBagForgivenessProp: String = (project.findProperty("levelPlayRewardedbagForgiveness") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedbagForgiveness")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedbagForgiveness")
    ?: ""

val levelPlayRewardedBidAdjustProp: String = (project.findProperty("levelPlayRewardedbidAdjust") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayRewardedbidAdjust")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayRewardedbidAdjust")
    ?: ""

val admobRewardedUndoTrickProp: String = (project.findProperty("admobRewardedUndoTrick") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedUndoTrick")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedUndoTrick")
    ?: ""

val admobRewardedPeekCardProp: String = (project.findProperty("admobRewardedPeekCard") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedPeekCard")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedPeekCard")
    ?: ""

val admobRewardedExtraBookProp: String = (project.findProperty("admobRewardedExtraBook") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedExtraBook")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedExtraBook")
    ?: ""

val admobRewardedReplayHandProp: String = (project.findProperty("admobRewardedReplayHand") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedReplayHand")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedReplayHand")
    ?: ""

val admobRewardedBagForgivenessProp: String = (project.findProperty("admobRewardedBagForgiveness") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedBagForgiveness")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedBagForgiveness")
    ?: ""

val admobRewardedBidAdjustProp: String = (project.findProperty("admobRewardedBidAdjust") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobRewardedBidAdjust")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobRewardedBidAdjust")
    ?: ""

val admobBannerAdUnitIdProp: String = (project.findProperty("admobBannerAdUnitId") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobBannerAdUnitId")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobBannerAdUnitId")
    ?: ""

val admobInterstitialAdUnitIdProp: String = (project.findProperty("admobInterstitialAdUnitId") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "admobInterstitialAdUnitId")
    ?: readSimpleProp(rootProject.file("local.properties"), "admobInterstitialAdUnitId")
    ?: ""

val levelPlayBannerAdUnitIdProp: String = (project.findProperty("levelPlayBannerAdUnitId") as? String)
    ?: readSimpleProp(rootProject.file("gradle.properties"), "levelPlayBannerAdUnitId")
    ?: readSimpleProp(rootProject.file("local.properties"), "levelPlayBannerAdUnitId")
    ?: ""

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
        versionCode = 101
        versionName = "2.0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_API_URL",
            "\"https://p0pr4ffvff.execute-api.us-east-1.amazonaws.com/prod\"")

        // Provide BASE_API_URL to the Android app via BuildConfig. Falls back to production API.
        val resolvedBaseApi = baseApiUrlProp ?: "https://p0pr4ffvff.execute-api.us-east-1.amazonaws.com/prod"
        buildConfigField("String", "BASE_API_URL", "\"${resolvedBaseApi}\"")
        buildConfigField("Boolean", "REWARDED_TEST_MODE", rewardedTestModeProp)
        buildConfigField("Boolean", "GOOGLE_ADS_ENABLED", googleAdsEnabledProp)
        buildConfigField("Boolean", "FACEBOOK_ADS_ENABLED", facebookAdsEnabledProp)
        buildConfigField("Boolean", "UNITY_ADS_ENABLED", unityAdsEnabledProp)
        buildConfigField("String",  "UNITY_GAME_ID",      "\"${unityGameIdProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_APP_KEY",              "\"${levelPlayAppKeyProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_INTERSTITIAL_AD_UNIT", "\"${levelPlayInterstitialAdUnitIdProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_UNDO_TRICK",      "\"${levelPlayRewardedUndoLastTrickProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_PEEK_CARD",       "\"${levelPlayRewardedPeekCardProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_EXTRA_BOOK",      "\"${levelPlayRewardedExtraBookProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_REPLAY_HAND",     "\"${levelPlayRewardedReplayHandProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_BAG_FORGIVENESS", "\"${levelPlayRewardedBagForgivenessProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_REWARDED_BID_ADJUST",      "\"${levelPlayRewardedBidAdjustProp}\"")
        buildConfigField("String",  "LEVEL_PLAY_BANNER_AD_UNIT",       "\"${levelPlayBannerAdUnitIdProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_UNDO_TRICK",      "\"${admobRewardedUndoTrickProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_PEEK_CARD",       "\"${admobRewardedPeekCardProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_EXTRA_BOOK",      "\"${admobRewardedExtraBookProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_REPLAY_HAND",     "\"${admobRewardedReplayHandProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_BAG_FORGIVENESS", "\"${admobRewardedBagForgivenessProp}\"")
        buildConfigField("String",  "ADMOB_REWARDED_BID_ADJUST",      "\"${admobRewardedBidAdjustProp}\"")
        buildConfigField("String",  "ADMOB_BANNER_AD_UNIT_ID",        "\"${admobBannerAdUnitIdProp}\"")
        buildConfigField("String",  "ADMOB_INTERSTITIAL_AD_UNIT_ID",  "\"${admobInterstitialAdUnitIdProp}\"")
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
    implementation(libs.unity.ads)
    implementation(libs.unity.mediation)
    implementation(libs.ironsource.admob.adapter)
    implementation(libs.ironsource.facebook.adapter)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}