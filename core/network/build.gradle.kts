plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.network"
}

dependencies {
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockwebserver3)
}
