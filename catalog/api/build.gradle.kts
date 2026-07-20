plugins { id("muxtv.kotlin.library") }

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
