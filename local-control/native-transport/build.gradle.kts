plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":local-control:wire-lab"))
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:tv-qr"))
    // Native TCP tests construct the QR using the public-only QR contract.
    // tv-qr's implementation dependency is intentionally not transitively visible.
    testImplementation(project(":local-control:qr-public"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
