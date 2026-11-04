plugins { id("muxtv.kotlin.library") }

dependencies {
    // Tink owns AES-GCM nonce generation and authentication. Do not invent a
    // cipher, KDF, PAKE or TLS substitute in this laboratory module.
    implementation(libs.tink)
    // The real QR parser, TV-local Ed25519 consent gate and signed wire
    // transcript are TEST fixtures only; no production source dependencies.
    testImplementation(project(":local-control:qr-public"))
    testImplementation(project(":local-control:enrollment"))
    testImplementation(project(":local-control:wire-lab"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
