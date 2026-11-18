plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":local-control:core"))
    // Neutral bounded plaintext schema ONLY; historical crypto lab stays isolated.
    implementation(project(":local-control:source-command-wire"))
    // Original TV QR/HPKE-owned AES-GCM only; no synthetic-MXR1 grant.
    implementation(project(":local-control:tv-qr"))
    implementation(project(":local-control:source-aead"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":local-control:mxh1-session-lab"))
    testImplementation(project(":local-control:enrollment"))
    testImplementation(project(":local-control:qr-public"))
}

tasks.test { useJUnit() }
