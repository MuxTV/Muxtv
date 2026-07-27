plugins { id("muxtv.kotlin.library") }

dependencies {
    testImplementation(project(":catalog:ingest"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
