plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":local-control:core"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnit() }
