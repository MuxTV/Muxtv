import groovy.json.JsonSlurper
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

val verifyCurrentRoomSchema = tasks.register("verifyCurrentRoomSchema") {
    group = "verification"
    description =
        "Verifies that the current generated Room schema is committed and matches the database version."

    val versionSource = layout.projectDirectory.file(
        "src/main/kotlin/app/muxtv/database/CurrentDatabaseMigrations.kt",
    )
    val schemaRoot = layout.projectDirectory.dir(
        "schemas/app.muxtv.database.MuxTvDatabase",
    )
    inputs.file(versionSource)
    inputs.dir(schemaRoot)

    doLast {
        val versionMatch = Regex(
            "CURRENT_DATABASE_VERSION\\s*=\\s*(\\d+)",
        ).find(versionSource.asFile.readText())
            ?: throw GradleException(
                "Unable to resolve CURRENT_DATABASE_VERSION from ${versionSource.asFile}.",
            )
        val currentVersion = versionMatch.groupValues[1].toInt()
        val schemaFile = schemaRoot.file("$currentVersion.json").asFile
        if (!schemaFile.isFile) {
            throw GradleException(
                "Missing generated Room schema for version $currentVersion: $schemaFile. " +
                    "Run :core:database:copyRoomSchemas and commit the exact generated artifact.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(schemaFile) as? Map<String, Any?>
            ?: throw GradleException("Room schema root must be a JSON object: $schemaFile")
        @Suppress("UNCHECKED_CAST")
        val database = root["database"] as? Map<String, Any?>
            ?: throw GradleException("Room schema has no database object: $schemaFile")
        val schemaVersion = (database["version"] as? Number)?.toInt()
            ?: throw GradleException("Room schema has no numeric database.version: $schemaFile")
        if (schemaVersion != currentVersion) {
            throw GradleException(
                "Room schema version mismatch: source=$currentVersion " +
                    "artifact=$schemaVersion ($schemaFile).",
            )
        }
        val identityHash = database["identityHash"] as? String
        if (identityHash.isNullOrBlank()) {
            throw GradleException("Room schema identityHash is missing: $schemaFile")
        }

        val relativeSchemaPath = schemaFile
            .relativeTo(rootProject.projectDir)
            .invariantSeparatorsPath
        val gitStatus = providers.exec {
            workingDir(rootProject.projectDir)
            commandLine(
                "git",
                "status",
                "--porcelain=v1",
                "--",
                relativeSchemaPath,
            )
        }.standardOutput.asText.get().trim()
        if (gitStatus.isNotEmpty()) {
            throw GradleException(
                "Generated Room schema is missing or differs from the committed artifact: " +
                    "$relativeSchemaPath ($gitStatus). Commit the exact output of " +
                    ":core:database:copyRoomSchemas.",
            )
        }

        logger.lifecycle(
            "Verified committed Room schema v$currentVersion " +
                "identity=$identityHash path=$relativeSchemaPath",
        )
    }
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
    implementation(libs.androidx.tracing)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room3.testing)
    androidTestImplementation(libs.truth)
}
