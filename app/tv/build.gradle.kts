plugins {
    id("muxtv.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.muxtv"
    defaultConfig {
        applicationId = "app.muxtv.tv"
        versionCode = 1001
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            optimization {
                enable = true
            }
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":catalog:importer"))
    implementation(project(":catalog:refresh"))
    implementation(project(":catalog:sync"))
    implementation(project(":catalog:onboarding"))
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))
    implementation(project(":feature:home"))
    implementation(project(":feature:channels"))
    implementation(project(":feature:guide"))
    implementation(project(":feature:search"))
    implementation(project(":feature:player"))
    implementation(project(":feature:sources"))
    implementation(project(":feature:doctor"))
    implementation(project(":player:media3"))
    implementation(project(":player:api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.tv.material)
    implementation(libs.media3.ui.compose)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    implementation(libs.work.runtime)
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark:macrobenchmark"))
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.okhttp.bom))
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}
