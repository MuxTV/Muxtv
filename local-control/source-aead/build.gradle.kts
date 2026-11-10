plugins { id("muxtv.kotlin.library") }

dependencies {
    // Typed bounded M3U/Xtream and secret-free reply vocabulary only.
    implementation(project(":local-control:source-command-wire"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
