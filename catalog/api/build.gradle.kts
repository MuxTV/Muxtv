plugins { id("muxtv.kotlin.library") }

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":player:api"))
    api(libs.coroutines.core)
    api(libs.paging.common)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
