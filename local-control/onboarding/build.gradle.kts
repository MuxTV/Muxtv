plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":local-control:core"))
    // Typed application plaintext schema ONLY; no raw TCP/HTTP route.
    implementation(project(":local-control:protected-channel"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnit() }
