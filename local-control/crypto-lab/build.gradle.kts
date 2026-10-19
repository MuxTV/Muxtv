plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(project(":local-control:wire-lab"))
}

tasks.test { useJUnit() }
