plugins { id("muxtv.android.library") }

android {
    namespace = "app.muxtv.catalog.importer"
}

dependencies {
    implementation(project(":catalog:ingest"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
