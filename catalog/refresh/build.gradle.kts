plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.catalog.refresh"
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":catalog:importer"))
    implementation(project(":catalog:ingest"))
    implementation(project(":core:credentials"))
    implementation(project(":core:network"))
    implementation(libs.coroutines.core)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(project(":core:database"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver3)
}