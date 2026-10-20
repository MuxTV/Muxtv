plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:wire-lab"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:crypto-lab"))
    testImplementation(project(":local-control:qr-public"))
}

tasks.test { useJUnit() }
