plugins { id("muxtv.kotlin.library") }

dependencies {
    api(project(":core:common"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnit() }
