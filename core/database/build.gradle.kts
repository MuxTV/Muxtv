import app.muxtv.buildlogic.VerifyCurrentRoomSchemaTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    id("muxtv.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

val catalogMeasurementsEnabled = providers.gradleProperty("catalogMeasurements")
    .orElse("false")
    .map { rawValue ->
        when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw GradleException("catalogMeasurements must be true or false.")
        }
    }

android {
    namespace = "app.muxtv.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "app.muxtv.database.test"
        if (!catalogMeasurementsEnabled.get()) {
            testInstrumentationRunnerArguments["notAnnotation"] =
                "app.muxtv.database.CatalogDatabaseMeasurement"
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val publishRoomSchemasEvidence = tasks.register<Sync>("publishRoomSchemasEvidence") {
    from(layout.projectDirectory.dir("schemas"))
    into(rootProject.layout.projectDirectory.dir(".work/evidence/room-schemas"))
}

val verifyCurrentRoomSchema = tasks.register<VerifyCurrentRoomSchemaTask>(
    "verifyCurrentRoomSchema",
) {
    group = "verification"
    description =
        "Verifies that the current generated Room schema is committed and matches the database version."
    versionSource.set(
        layout.projectDirectory.file(
            "src/main/kotlin/app/muxtv/database/CurrentDatabaseMigrations.kt",
        ),
    )
    schemaRoot.set(
        layout.projectDirectory.dir(
            "schemas/app.muxtv.database.MuxTvDatabase",
        ),
    )
    repositoryRoot.set(rootProject.layout.projectDirectory)
}

tasks.matching { it.name == "copyRoomSchemas" }.configureEach {
    finalizedBy(publishRoomSchemasEvidence, verifyCurrentRoomSchema)
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)
    implementation(libs.room3.runtime)
    implementation(libs.room3.paging)
    implementation(libs.paging.runtime)
    implementation(libs.androidx.tracing)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(project(":player:api"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room3.testing)
    androidTestImplementation(libs.paging.testing)
    androidTestImplementation(libs.truth)
}
