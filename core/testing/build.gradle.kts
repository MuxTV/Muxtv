plugins { id("muxtv.kotlin.library") }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
