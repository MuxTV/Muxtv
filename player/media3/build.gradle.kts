import org.gradle.api.GradleException

plugins {
    id("muxtv.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val playerProxyMeasurementsEnabled = providers.gradleProperty("playerProxyMeasurements")
    .orElse("false")
    .map { rawValue ->
        when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw GradleException("playerProxyMeasurements must be true or false.")
        }
    }

android {
    namespace = "app.muxtv.player.media3"
    buildFeatures { compose = true }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!playerProxyMeasurementsEnabled.get()) {
            testInstrumentationRunnerArguments["notAnnotation"] =
                "app.muxtv.player.media3.PlayerProxyMeasurement"
        }
    }
    testOptions {
        // Media3 model classes (TrackGroup, Format) call android.text helpers in constructors;
        // default stubs keep projector/projection host tests free of an instrumentation harness.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":player:api"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)

    // Keep ExoPlayer, session and UI artifacts on the single catalog Media3 version. The external
    // surface path depends on the SessionResult.INFO_CANCELLED handling fixed upstream in 1.11.0.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    api(libs.media3.session)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.okhttp.bom))
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.truth)
}
