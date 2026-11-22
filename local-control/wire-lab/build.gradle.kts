plugins { id("muxtv.kotlin.library") }

dependencies {
    // Real Tink Ed25519 signature verification is TEST ONLY. The wire codec
    // remains transport-agnostic, secret-free and unauthenticated.
    testImplementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
