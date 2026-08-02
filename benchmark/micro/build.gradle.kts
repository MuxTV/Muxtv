plugins {
    id("muxtv.android.library")
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "app.muxtv.benchmark.micro"
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }
    buildTypes {
        debug { isDebuggable = false }
        release { isDebuggable = false }
    }
}

dependencies {
    androidTestImplementation(project(":catalog:ingest"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.benchmark.junit4)
}
