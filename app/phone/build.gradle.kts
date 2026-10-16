plugins {
    id("muxtv.android.application")
}

android {
    namespace = "app.muxtv.phone"
    defaultConfig {
        applicationId = "app.muxtv.phone"
        versionCode = 1
        versionName = "0.1.0-lc04-preview"
    }
}

dependencies {
    implementation(project(":local-control:qr-contract"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
}
