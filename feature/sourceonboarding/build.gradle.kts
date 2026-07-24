plugins {
    id("muxtv.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.muxtv.feature.sourceonboarding"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":catalog:refresh"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.tv.material)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
