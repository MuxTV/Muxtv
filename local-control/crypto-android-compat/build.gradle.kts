plugins {
    id("muxtv.android.library")
}

android {
    namespace = "app.muxtv.localcontrol.cryptoandroidcompat"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation(project(":local-control:crypto-lab"))
    androidTestImplementation(libs.tink)
    androidTestImplementation(project(":local-control:tv-qr"))
    androidTestImplementation(project(":local-control:enrollment"))
    androidTestImplementation(project(":local-control:qr-public"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
