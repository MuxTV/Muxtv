plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:qr-public"))
    implementation(project(":local-control:wire-lab"))
    // Original TV QR owner reuses the accepted synthetic MXH1 KDF; no new QR.
    implementation(project(":local-control:protected-channel"))
    // QR-owned verified pending key exporter; no HTTP or SourceOnboarding.
    implementation(project(":local-control:source-aead"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:crypto-lab"))
    testImplementation(project(":local-control:mxh1-session-lab"))
    // Only integration tests decode typed source requests; no TV runtime dependency.
    testImplementation(project(":local-control:source-command-wire"))
}

tasks.test { useJUnit() }
