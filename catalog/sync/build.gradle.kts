plugins {
    id("muxtv.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.muxtv.catalog.sync"
}

dependencies {
    implementation(project(":catalog:refresh"))
    implementation(project(":core:credentials"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)
    implementation(libs.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
