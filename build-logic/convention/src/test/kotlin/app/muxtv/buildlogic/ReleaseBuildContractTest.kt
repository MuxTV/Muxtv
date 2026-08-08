package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseBuildContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `closed alpha release has immutable identity and optimized shrinking`() {
        val applicationBuild = repositoryRoot.resolve("app/tv/build.gradle.kts").readText()
        val android = applicationBuild.block("android")
        val defaultConfig = android.block("defaultConfig")
        val release = android.block("buildTypes").block("release")
        val optimization = release.block("optimization")

        listOf(
            "versionCode = 1001",
            "versionName = \"0.1.0-alpha.1\"",
        ).forEach { expected -> assertContains(defaultConfig, expected) }
        assertContains(optimization, "enable = true")
        listOf(
            "isMinifyEnabled",
            "isShrinkResources",
            "getDefaultProguardFile",
            "proguardFiles",
        ).forEach { legacyDsl ->
            assertFalse(
                release.contains(legacyDsl),
                "AGP 9.3 release must use the optimization DSL, not $legacyDsl.",
            )
        }
        assertTrue(
            repositoryRoot.resolve("app/tv/src/main/keepRules/muxtv.keep").isFile,
            "Missing AGP 9.3 app keep-rules source set.",
        )
    }

    private fun String.block(name: String): String {
        val marker = "$name {"
        val markerIndex = indexOf(marker)
        require(markerIndex >= 0) { "Missing $name block." }
        val openingBrace = markerIndex + marker.lastIndex
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(openingBrace + 1, index)
                }
            }
        }
        error("Unclosed $name block.")
    }
}
