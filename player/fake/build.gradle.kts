plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":player:api"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnit() }
