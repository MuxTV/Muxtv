plugins { id("muxtv.kotlin.library") }

dependencies {
    // Tink owns AES-GCM nonce generation and authentication. Do not invent a
    // cipher, KDF, PAKE or TLS substitute in this laboratory module.
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
