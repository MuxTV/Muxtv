plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.androidx.tracing)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
