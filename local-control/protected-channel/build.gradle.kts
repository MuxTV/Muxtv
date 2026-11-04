plugins { id("muxtv.kotlin.library") }

dependencies {
    // Tink owns AES-GCM/HPKE authenticated encryption and signature primitives.
    // The separate synthetic HKDF experiment follows RFC 5869 via JCA HmacSHA256;
    // it neither invents a KDF nor implements a production key handshake.
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
