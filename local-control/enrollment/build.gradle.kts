plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":local-control:qr-public"))
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
