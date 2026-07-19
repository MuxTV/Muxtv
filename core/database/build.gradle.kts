plugins {
    id("muxtv.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "app.muxtv.database"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    implementation(libs.room3.runtime)
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
