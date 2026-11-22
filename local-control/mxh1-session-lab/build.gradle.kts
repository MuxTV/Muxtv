plugins { id("muxtv.kotlin.library") }

dependencies {
    // Reviewable JVM-only proof of MXH1 cryptographic state transitions.
    // This is NOT a TCP, Android, provider or arbitrary-payload capability.
    implementation(project(":local-control:wire-lab"))
    implementation(project(":local-control:qr-public"))
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:protected-channel"))
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
