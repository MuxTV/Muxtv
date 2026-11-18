plugins { id("muxtv.kotlin.library") }

dependencies {
    // Only a four-frame synthetic handshake. No provider/source credentials.
    implementation(project(":local-control:tv-qr"))
    // Typed APPDATA port, never raw source keys or provider APIs.
    implementation(project(":local-control:source-aead"))
    // The first APPDATA proof permits only the fixed Cancel(1) vocabulary.
    implementation(project(":local-control:source-command-wire"))
    implementation(project(":local-control:mxh1-session-lab"))
    implementation(project(":local-control:wire-lab"))
    implementation(project(":local-control:native-transport"))
    testImplementation(project(":local-control:enrollment"))
    testImplementation(project(":local-control:qr-public"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
