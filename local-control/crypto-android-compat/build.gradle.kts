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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
