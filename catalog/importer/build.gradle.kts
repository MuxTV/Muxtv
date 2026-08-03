plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.catalog.importer"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":catalog:ingest"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)
    implementation(libs.androidx.tracing)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(project(":catalog:api"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
