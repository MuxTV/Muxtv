plugins {
    id("muxtv.android.application")
}

android {
    namespace = "app.muxtv.phone"
    defaultConfig {
        applicationId = "app.muxtv.phone.preview"
        versionCode = 1
        versionName = "0.0.1-qr-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Preview-only offline parsing. The crypto-lab is not a production channel.
    implementation(project(":local-control:crypto-lab"))
    implementation(libs.androidx.activity)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
