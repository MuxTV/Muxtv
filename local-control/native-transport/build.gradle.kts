plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":local-control:wire-lab"))
    implementation(project(":local-control:enrollment"))
    implementation(project(":local-control:tv-qr"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
