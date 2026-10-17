plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":local-control:qr-public"))
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:tv-qr"))
}

tasks.test { useJUnit() }
