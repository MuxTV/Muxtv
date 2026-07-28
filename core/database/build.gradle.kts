import org.gradle.api.GradleException

plugins {
    id("muxtv.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

val catalogMeasurementsEnabled = providers.gradleProperty("catalogMeasurements")
    .orElse("false")
    .map { rawValue ->
        when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw GradleException("catalogMeasurements must be true or false.")
        }
    }

android {
    namespace = "app.muxtv.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "app.muxtv.database.test"
        if (!catalogMeasurementsEnabled.get()) {
            testInstrumentationRunnerArguments["notAnnotation"] =
                "app.muxtv.database.CatalogDatabaseMeasurement"
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    implementation(libs.room3.runtime)
    implementation(libs.androidx.tracing)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room3.testing)
    androidTestImplementation(libs.truth)
}
