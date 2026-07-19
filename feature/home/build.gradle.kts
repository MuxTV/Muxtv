plugins {
    id("muxtv.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.muxtv.feature.home"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":player:api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
}
