import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(project(":catalog:ingest"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

val mainSourceSet = extensions.getByType<SourceSetContainer>().getByName("main")

val corpusProfile = providers.gradleProperty("corpusProfile").orElse("small-1k")
val corpusSeed = providers.gradleProperty("corpusSeed").orElse("20260728")
val corpusSourceCommit = providers.gradleProperty("corpusSourceCommit")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
val corpusOutputDirectory = providers.gradleProperty("corpusOutputDirectory")
    .orElse(layout.buildDirectory.dir("corpus").map { it.asFile.absolutePath })
val corpusOverwrite = providers.gradleProperty("corpusOverwrite").orElse("false")

val corpusCommandArguments = mutableListOf(
    "--profile",
    corpusProfile.get(),
    "--seed",
    corpusSeed.get(),
    "--output",
    corpusOutputDirectory.get(),
)
corpusSourceCommit.orNull?.let { sourceCommit ->
    corpusCommandArguments += listOf("--source-commit", sourceCommit)
}
when (corpusOverwrite.get().lowercase()) {
    "false" -> Unit
    "true" -> corpusCommandArguments += "--overwrite"
    else -> throw GradleException("corpusOverwrite must be true or false.")
}

tasks.register<JavaExec>("generateM3uCorpus") {
    group = "verification"
    description = "Generates a deterministic M3U corpus and canonical manifest artifact pair."
    mainClass.set("app.muxtv.testing.iptv.M3uCorpusCommandKt")
    classpath = mainSourceSet.runtimeClasspath
    args = corpusCommandArguments
}

val measurementProfile = providers.gradleProperty("measurementProfile").orElse("small-1k")
val measurementSeed = providers.gradleProperty("measurementSeed").orElse("20260728")
val measurementSourceCommit = providers.gradleProperty("measurementSourceCommit")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
val measurementWarmups = providers.gradleProperty("measurementWarmups").orElse("2")
val measurementIterations = providers.gradleProperty("measurementIterations").orElse("5")
val measurementRunnerLabel = providers.gradleProperty("measurementRunnerLabel").orElse("local")
val measurementOutput = providers.gradleProperty("measurementOutput")
    .orElse(layout.buildDirectory.file("measurements/m3u-parse-report.json").map { it.asFile.absolutePath })

val measurementCommandArguments = mutableListOf(
    "--profile",
    measurementProfile.get(),
    "--seed",
    measurementSeed.get(),
    "--warmups",
    measurementWarmups.get(),
    "--iterations",
    measurementIterations.get(),
    "--runner-label",
    measurementRunnerLabel.get(),
    "--output",
    measurementOutput.get(),
)
measurementSourceCommit.orNull?.let { sourceCommit ->
    measurementCommandArguments += listOf("--source-commit", sourceCommit)
}

tasks.register<JavaExec>("measureM3uParse") {
    group = "verification"
    description = "Produces descriptive M3U parser timing/allocation evidence without thresholds."
    mainClass.set("app.muxtv.testing.iptv.M3uParseMeasurementMain")
    classpath = mainSourceSet.runtimeClasspath
    args = measurementCommandArguments
}

tasks.test { useJUnit() }
