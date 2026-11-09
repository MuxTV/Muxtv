plugins { id("muxtv.kotlin.library") }

dependencies {
    // Only a four-frame synthetic handshake. No provider/source credentials.
    implementation(project(":local-control:tv-qr"))
    implementation(project(":local-control:mxh1-session-lab"))
    implementation(project(":local-control:wire-lab"))
    implementation(project(":local-control:native-transport"))
    testImplementation(project(":local-control:enrollment"))
    testImplementation(project(":local-control:qr-public"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
