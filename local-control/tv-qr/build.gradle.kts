plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:qr-public"))
    implementation(project(":local-control:wire-lab"))
    // Original TV QR owner reuses the accepted synthetic MXH1 KDF; no new QR.
    implementation(project(":local-control:protected-channel"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:crypto-lab"))
    testImplementation(project(":local-control:mxh1-session-lab"))
}

tasks.test { useJUnit() }
