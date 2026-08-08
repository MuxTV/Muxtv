plugins {
    id("muxtv.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.muxtv.feature.doctor"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":player:api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
