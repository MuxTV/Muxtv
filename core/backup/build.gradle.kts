plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
