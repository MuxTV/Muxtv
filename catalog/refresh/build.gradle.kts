plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.catalog.refresh"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":catalog:importer"))
    implementation(project(":catalog:ingest"))
    implementation(project(":core:credentials"))
    implementation(project(":core:network"))
    implementation(project(":player:api"))
    implementation(libs.coroutines.core)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(project(":core:database"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver3)

    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockwebserver3)
}
