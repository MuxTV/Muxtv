plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    testImplementation(project(":local-control:crypto-lab"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
