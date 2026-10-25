plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":local-control:qr-public"))
    implementation(project(":local-control:enrollment"))
    // Only public enrollment/probe facilities. No sockets, Android or IPTV source API.
    testImplementation(project(":local-control:tv-qr"))
    testImplementation(project(":local-control:native-transport"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
