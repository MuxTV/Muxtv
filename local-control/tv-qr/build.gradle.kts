plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:crypto-lab"))
    testImplementation(project(":local-control:qr-public"))
}

tasks.test { useJUnit() }
