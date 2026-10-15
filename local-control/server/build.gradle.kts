plugins {
    id("muxtv.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":local-control:core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
tasks.test { useJUnit() }
