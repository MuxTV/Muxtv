plugins { id("muxtv.android.library") }

android { namespace = "app.muxtv.player.media3" }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":player:api"))
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
