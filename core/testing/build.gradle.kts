import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

plugins { id("muxtv.kotlin.library") }

dependencies {
    testImplementation(project(":catalog:ingest"))
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

tasks.test { useJUnit() }
