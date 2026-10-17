plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:crypto-lab"))
}

tasks.test { useJUnit() }
