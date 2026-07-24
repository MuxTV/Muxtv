plugins { id("muxtv.android.library") }

android { namespace = "app.muxtv.catalog.onboarding" }

dependencies {
    implementation(project(":catalog:refresh"))
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
