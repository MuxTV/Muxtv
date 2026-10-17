plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
