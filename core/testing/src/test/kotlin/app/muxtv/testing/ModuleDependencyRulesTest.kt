package app.muxtv.testing

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Architecture guard on real dependencies, not on documentation text.
 *
 * Enforcement happens where actual dependencies surface:
 * - Kotlin `import` statements (code, not comments or strings);
 * - dependency declarations in `*.gradle.kts` (`project(...)` coordinates, `libs.*` catalog
 *   accessors, direct `"group:artifact"` coordinates).
 *
 * Allowlist entries are concrete prefixes (e.g. `androidx.paging` for the KMP paging-common
 * dependency accepted in #157), so doc words like "Media3" or "Room" in KDoc can neither trip
 * the guard nor mask a real dependency.
 */
class ModuleDependencyRulesTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    private val forbiddenImportPrefixes = listOf(
        "android.",
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

    /** Direct Maven coordinates need group/artifact rules, not Kotlin package-prefix rules. */
    private val forbiddenCoordinatePrefixes = listOf(
        "androidx.",
        "com.android.",
        "com.google.android.exoplayer",
        "com.squareup.okhttp3:",
        "com.google.dagger:",
    )

    private val forbiddenCoordinateFragments = listOf(
        ":media3-",
        ":room-",
        ":hilt-",
        ":compose-",
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
                assertThat(isForbiddenImport(importFqcn, allowed)).isFalse()
            }
            declaredLibAccessors(module).forEach { accessor ->
                assertThat(isForbiddenAccessor(accessor)).isFalse()
            }
            declaredProjectModules(module).forEach { projectModule ->
                assertThat(isForbiddenProjectModule(projectModule)).isFalse()
            }
            declaredCoordinates(module).forEach { coordinate ->
                assertThat(isForbiddenCoordinate(coordinate, allowed)).isFalse()
            }
        }
    }

    @Test
    fun `feature does not depend on database or media3 implementation`() {
        val build = root.resolve("feature/home/build.gradle.kts").readText()
        assertThat(build).doesNotContain(":core:database")
        assertThat(build).doesNotContain(":player:media3")
    }

    /** Mutation-style tests protect the guard itself against false-green parser regressions. */
    @Test
    fun `project dependency parser preserves colon and rejects forbidden module`() {
        val module = parseProjectModule("implementation(project(\":core:database\"))")
        assertThat(module).isEqualTo(":core:database")
        assertThat(isForbiddenProjectModule(checkNotNull(module))).isTrue()

        val feature = parseProjectModule("api(project(\":feature:player\"))")
        assertThat(feature).isEqualTo(":feature:player")
        assertThat(isForbiddenProjectModule(checkNotNull(feature))).isTrue()
    }

    @Test
    fun `project dependency parser covers named path syntax`() {
        val module = parseProjectModule("implementation(project(path = \":player:media3\"))")
        assertThat(module).isEqualTo(":player:media3")
        assertThat(isForbiddenProjectModule(checkNotNull(module))).isTrue()
    }

    @Test
    fun `string coordinate parser returns coordinate rather than quote and rejects platform artifacts`() {
        val room = parseStringCoordinate("implementation(\"androidx.room:room-runtime:3.0.0\")")
        assertThat(room).isEqualTo("androidx.room:room-runtime:3.0.0")
        assertThat(isForbiddenCoordinate(checkNotNull(room), emptyList())).isTrue()

        val okhttp = parseStringCoordinate("implementation(\"com.squareup.okhttp3:okhttp:5.1.0\")")
        assertThat(isForbiddenCoordinate(checkNotNull(okhttp), emptyList())).isTrue()

        val hilt = parseStringCoordinate("implementation(\"com.google.dagger:hilt-android:2.60\")")
        assertThat(isForbiddenCoordinate(checkNotNull(hilt), emptyList())).isTrue()
    }

    @Test
    fun `version catalog parser rejects forbidden platform accessor`() {
        val accessor = parseLibAccessor("implementation(libs.androidx.media3.common)")
        assertThat(accessor).isEqualTo("androidx.media3.common")
        assertThat(isForbiddenAccessor(checkNotNull(accessor))).isTrue()
    }

    @Test
    fun `raw android imports are forbidden in pure contracts`() {
        assertThat(isForbiddenImport("android.net.Uri", emptyList())).isTrue()
        assertThat(isForbiddenImport("androidx.compose.runtime.Composable", emptyList())).isTrue()
        assertThat(isForbiddenImport("kotlinx.coroutines.flow.Flow", emptyList())).isFalse()
    }

    @Test
    fun `paging allowlist remains narrow for imports and direct coordinates`() {
        val allowed = allowedImportPrefixes.getValue("catalog/api")
        assertThat(isForbiddenImport("androidx.paging.PagingData", allowed)).isFalse()
        assertThat(isForbiddenImport("androidx.room.Room", allowed)).isTrue()
        assertThat(isForbiddenCoordinate("androidx.paging:paging-common:3.4.0", allowed)).isFalse()
        assertThat(isForbiddenCoordinate("androidx.room:room-runtime:3.0.0", allowed)).isTrue()
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
        dependencyLines(module).mapNotNull(::parseLibAccessor)

    private fun declaredProjectModules(module: String): List<String> =
        dependencyLines(module).mapNotNull(::parseProjectModule)

    private fun declaredCoordinates(module: String): List<String> =
        dependencyLines(module).mapNotNull(::parseStringCoordinate)

    private fun dependencyLines(module: String): List<String> =
        root.resolve(module).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .flatMap { file ->
                file.readLines().filter { line ->
                    DEPENDENCY_DECLARATION_REGEX.containsMatchIn(line)
                }
            }
            .toList()

    private fun parseLibAccessor(line: String): String? =
        LIBS_ACCESSOR_REGEX.find(line)?.groupValues?.get(1)

    private fun parseProjectModule(line: String): String? =
        PROJECT_MODULE_REGEX.find(line)?.groupValues?.get(1)

    private fun parseStringCoordinate(line: String): String? =
        STRING_COORDINATE_REGEX.find(line)?.groupValues?.get(2)

    private fun isForbiddenImport(importFqcn: String, allowed: List<String>): Boolean =
        allowed.none { importFqcn.startsWith(it) } &&
            forbiddenImportPrefixes.any { importFqcn.startsWith(it) }

    private fun isForbiddenAccessor(accessor: String): Boolean =
        forbiddenAccessorFragments.any { fragment -> accessor.contains(fragment) }

    private fun isForbiddenProjectModule(projectModule: String): Boolean =
        forbiddenProjectModules.any { projectModule.startsWith(it) }

    private fun isForbiddenCoordinate(coordinate: String, allowed: List<String>): Boolean {
        if (allowed.any { coordinate.startsWith(it) }) return false
        return forbiddenCoordinatePrefixes.any { coordinate.startsWith(it) } ||
            forbiddenCoordinateFragments.any { coordinate.contains(it) }
    }

    private companion object {
        val IMPORT_REGEX = Regex("^import\\s+([A-Za-z0-9_.]+)")
        val LIBS_ACCESSOR_REGEX = Regex("""libs\.([a-zA-Z0-9.]+)""")
        val PROJECT_MODULE_REGEX =
            Regex("""project\(\s*(?:path\s*=\s*)?"(:[a-zA-Z0-9-:]+)"\s*\)""")
        val STRING_COORDINATE_REGEX = Regex("""(["'])([a-zA-Z0-9_.:-]+)\1""")
        val DEPENDENCY_DECLARATION_REGEX =
            Regex("""(api|implementation|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor)\(""")
    }
}
