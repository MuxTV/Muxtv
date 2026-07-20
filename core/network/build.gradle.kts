plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.network"
}

dependencies {
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockwebserver3)
}
