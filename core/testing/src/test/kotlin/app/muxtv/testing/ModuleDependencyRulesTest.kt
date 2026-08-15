package app.muxtv.testing

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Architecture guard on real dependencies, not on documentation text.
 *
 * Enforcement happens where actual dependencies surface:
 * - Kotlin `import` statements (code, not comments or strings);
 * - dependency declarations in `*.gradle.kts` (`project(":…")` coordinates, `libs.*` catalog
 *   accessors, `"group:artifact"` coordinates).
 *
 * Allowlist entries are concrete prefixes (e.g. `androidx.paging` for the KMP paging-common
 * dependency accepted in #157), so doc words like "Media3" or "Room" in KDoc can neither trip
 * the guard nor mask a real dependency.
 */
class ModuleDependencyRulesTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    private val forbiddenImportPrefixes = listOf(
        "androidx.",
        "com.android.",
        "com.google.android.exoplayer",
        "okhttp3",
        "dagger.",
    )

    /** Concrete allowed prefixes per module. */
    private val allowedImportPrefixes = mapOf(
        "catalog/api" to listOf("androidx.paging"),
    )

    /** Version-catalog accessor fragments that resolve to platform libraries. */
    private val forbiddenAccessorFragments = listOf(
        "media3",
        "exoplayer",
        "room",
        "okhttp",
        "hilt",
        "compose",
    )

    private val forbiddenProjectModules = listOf(
        ":core:database",
        ":player:media3",
        ":feature:",
        ":app:",
    )

    @Test
    fun `pure contract modules reference no platform libraries`() {
        listOf("core/model", "catalog/api", "player/api").forEach { module ->
            val allowed = allowedImportPrefixes[module].orEmpty()
            kotlinImports(module).forEach { importFqcn ->
                if (allowed.none { importFqcn.startsWith(it) }) {
                    forbiddenImportPrefixes.forEach { prefix ->
                        assertThat(importFqcn.startsWith(prefix)).isFalse()
                    }
                }
            }
            declaredLibAccessors(module).forEach { accessor ->
                forbiddenAccessorFragments.forEach { fragment ->
                    assertThat(accessor).doesNotContain(fragment)
                }
            }
            declaredProjectModules(module).forEach { projectModule ->
                forbiddenProjectModules.forEach { forbiddenModule ->
                    assertThat(projectModule.startsWith(forbiddenModule)).isFalse()
                }
            }
            declaredCoordinates(module).forEach { coordinate ->
                if (allowed.none { coordinate.startsWith(it) }) {
                    forbiddenImportPrefixes.forEach { prefix ->
                        assertThat(coordinate.startsWith(prefix)).isFalse()
                    }
                }
            }
        }
    }

    @Test
    fun `feature does not depend on database or media3 implementation`() {
        val build = root.resolve("feature/home/build.gradle.kts").readText()
        assertThat(build).doesNotContain(":core:database")
        assertThat(build).doesNotContain(":player:media3")
    }

    private fun kotlinImports(module: String): List<String> =
        root.resolve(module).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapNotNull { line ->
                    IMPORT_REGEX.find(line.trim())?.groupValues?.get(1)
                }
            }
            .toList()

    private fun declaredLibAccessors(module: String): List<String> =
        dependencyLines(module).mapNotNull { line ->
            LIBS_ACCESSOR_REGEX.find(line)?.groupValues?.get(1)
        }

    private fun declaredProjectModules(module: String): List<String> =
        dependencyLines(module).mapNotNull { line ->
            PROJECT_MODULE_REGEX.find(line)?.groupValues?.get(1)
        }

    private fun declaredCoordinates(module: String): List<String> =
        dependencyLines(module).mapNotNull { line ->
            STRING_COORDINATE_REGEX.find(line)?.groupValues?.get(1)
        }

    private fun dependencyLines(module: String): List<String> =
        root.resolve(module).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .flatMap { file ->
                file.readLines().filter { line ->
                    DEPENDENCY_DECLARATION_REGEX.containsMatchIn(line)
                }
            }
            .toList()

    private companion object {
        val IMPORT_REGEX = Regex("^import\\s+([A-Za-z0-9_.]+)")
        val LIBS_ACCESSOR_REGEX = Regex("""libs\.([a-zA-Z0-9.]+)""")
        val PROJECT_MODULE_REGEX = Regex("""project\(":([a-zA-Z0-9-:]+)"\)""")
        val STRING_COORDINATE_REGEX = Regex("""(["'])([a-zA-Z0-9_.:-]+)\1""")
        val DEPENDENCY_DECLARATION_REGEX =
            Regex("""(api|implementation|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor)\(""")
    }
}
