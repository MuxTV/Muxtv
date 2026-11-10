plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":local-control:core"))
    // Neutral bounded plaintext schema ONLY; historical crypto lab stays isolated.
    implementation(project(":local-control:source-command-wire"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnit() }
