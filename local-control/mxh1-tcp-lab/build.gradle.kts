plugins { id("muxtv.kotlin.library") }

dependencies {
    // Research-only, never linked into either Android app. This module
    // composes the original TV QR owner and existing synthetic phone lab
    // without permission, source, HTTP or data-plane dependencies.
    implementation(project(":local-control:tv-qr"))
    implementation(project(":local-control:mxh1-session-lab"))
    implementation(project(":local-control:wire-lab"))
    testImplementation(project(":local-control:enrollment"))
    testImplementation(project(":local-control:qr-public"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
