plugins {
    id("muxtv.android.application")
}

android {
    namespace = "app.muxtv.phone"
    defaultConfig {
        applicationId = "app.muxtv.phone"
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":local-control:qr-public"))
    implementation(libs.activity.compose)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
