plugins {
    id("muxtv.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.muxtv.database"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
