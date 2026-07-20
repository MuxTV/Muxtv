plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.credentials"
}

dependencies {
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
