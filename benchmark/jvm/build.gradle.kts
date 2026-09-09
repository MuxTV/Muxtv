plugins {
    id("muxtv.kotlin.library")
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":benchmark:competitive"))

    add("jmh", project(":catalog:api"))
    add("jmh", project(":catalog:ingest"))
    add("jmh", project(":player:api"))
    add("jmh", libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }

val dryRun = providers.gradleProperty("muxtvJmhDryRun").map(String::toBoolean).orElse(false)
val c06JmhOnly = providers.gradleProperty("muxtvC06JmhOnly").map(String::toBoolean).orElse(false)

jmh {
    jmhVersion = "1.37"
    warmupIterations = if (dryRun.get()) 1 else 5
    iterations = if (dryRun.get()) 1 else 5
    fork = if (dryRun.get()) 1 else 2
    timeOnIteration = if (dryRun.get()) "100ms" else "1s"
    warmup = if (dryRun.get()) "100ms" else "1s"
    profilers = listOf("gc")
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json").get().asFile
    humanOutputFile = layout.buildDirectory.file("reports/jmh/human.txt").get().asFile
    failOnError = true
    if (c06JmhOnly.get()) {
        includes = listOf(".*EpgMatchingBenchmark.*")
    }
}
