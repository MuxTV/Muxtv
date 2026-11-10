plugins { id("muxtv.kotlin.library") }

// Structural plaintext command DTOs only: no crypto, sockets, Android,
// credential store, SourceOnboarding, server or internet dependency.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
