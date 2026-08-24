plugins {
    `kotlin-dsl`
}

group = "app.muxtv.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:9.4.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        register("muxtvAndroidApplication") {
            id = "muxtv.android.application"
            implementationClass = "app.muxtv.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("muxtvAndroidLibrary") {
            id = "muxtv.android.library"
            implementationClass = "app.muxtv.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("muxtvKotlinLibrary") {
            id = "muxtv.kotlin.library"
            implementationClass = "app.muxtv.buildlogic.KotlinLibraryConventionPlugin"
        }
    }
}
