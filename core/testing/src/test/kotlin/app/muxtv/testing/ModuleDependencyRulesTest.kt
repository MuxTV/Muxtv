package app.muxtv.testing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Architecture guard on real dependencies, not on documentation text.
 *
 * Enforcement happens where actual dependencies surface:
 * - Kotlin `import` statements (code, not comments or strings);
 * - dependency declarations in `*.gradle.kts`, parsed as complete balanced calls so multiline
 *   declarations cannot evade the guard;
 * - version-catalog accessors resolved through `gradle/libs.versions.toml` to their actual
 *   `group:artifact` module rather than guessed from the accessor name;
 * - direct Maven coordinates and project-module coordinates.
 *
 * The only platform-adjacent exception accepted for the contract layer by #157 is
 * Paging Common's `PagingData` type/module. The exception is deliberately exact rather than an
 * `androidx.paging.*` prefix so Paging Runtime/Compose cannot silently enter `catalog/api`.
 */
class ModuleDependencyRulesTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val versionCatalogModules by lazy { readVersionCatalogModules() }

    private val forbiddenImportPrefixes = listOf(
        "android.",
        "androidx.",
        "com.android.",
        "com.google.android.exoplayer",
        "okhttp3",
        "dagger.",
    )

    private val allowedImports = mapOf(
        "catalog/api" to setOf("androidx.paging.PagingData"),
    )

    private val allowedModules = mapOf(
        "catalog/api" to setOf("androidx.paging:paging-common"),
    )

    /** Direct/resolved Maven modules need group/artifact rules, not Kotlin package-prefix rules. */
    private val forbiddenModulePrefixes = listOf(
        "androidx.",
        "com.android.",
        "com.google.android.exoplayer",
        "com.squareup.okhttp3:",
        "com.google.dagger:",
    )

    private val forbiddenModuleFragments = listOf(
        ":media3-",
        ":room-",
        ":room3-",
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
            val moduleAllowedImports = allowedImports[module].orEmpty()
            val moduleAllowedModules = allowedModules[module].orEmpty()

            kotlinImports(module).forEach { importFqcn ->
                assertThat(isForbiddenImport(importFqcn, moduleAllowedImports)).isFalse()
            }
            declaredProjectModules(module).forEach { projectModule ->
                assertThat(isForbiddenProjectModule(projectModule)).isFalse()
            }
            declaredCoordinates(module).forEach { coordinate ->
                assertThat(isForbiddenCoordinate(coordinate, moduleAllowedModules)).isFalse()
            }
            declaredLibAccessors(module).forEach { accessor ->
                val resolvedModule = versionCatalogModules[accessor]
                assertWithMessage("resolved version-catalog module for libs.$accessor")
                    .that(resolvedModule)
                    .isNotNull()
                assertWithMessage("resolved version-catalog module for libs.$accessor")
                    .that(isForbiddenCoordinate(checkNotNull(resolvedModule), moduleAllowedModules))
                    .isFalse()
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
    fun `dependency declaration parser preserves multiline nested project call`() {
        val declarations = parseDependencyDeclarations(
            """
            dependencies {
                implementation(
                    project(
                        path = ":core:database",
                    ),
                )
            }
            """.trimIndent(),
        )

        assertThat(declarations).hasSize(1)
        val module = parseProjectModule(declarations.single())
        assertThat(module).isEqualTo(":core:database")
        assertThat(isForbiddenProjectModule(checkNotNull(module))).isTrue()
    }

    @Test
    fun `string coordinate parser returns coordinate rather than quote and rejects platform artifacts`() {
        val room = parseStringCoordinate("implementation(\"androidx.room:room-runtime:3.0.0\")")
        assertThat(room).isEqualTo("androidx.room:room-runtime:3.0.0")
        assertThat(isForbiddenCoordinate(checkNotNull(room), emptySet())).isTrue()

        val okhttp = parseStringCoordinate("implementation(\"com.squareup.okhttp3:okhttp:5.1.0\")")
        assertThat(isForbiddenCoordinate(checkNotNull(okhttp), emptySet())).isTrue()

        val hilt = parseStringCoordinate("implementation(\"com.google.dagger:hilt-android:2.60\")")
        assertThat(isForbiddenCoordinate(checkNotNull(hilt), emptySet())).isTrue()
    }

    @Test
    fun `version catalog accessor resolves actual module instead of trusting alias name`() {
        val catalog = mapOf(
            "safe.utility" to "androidx.room3:room3-runtime",
            "plain.coroutines" to "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        )

        val hiddenPlatformModule = resolveCatalogModule("safe.utility", catalog)
        val allowedJvmModule = resolveCatalogModule("plain.coroutines", catalog)

        assertThat(hiddenPlatformModule).isEqualTo("androidx.room3:room3-runtime")
        assertThat(isForbiddenCoordinate(checkNotNull(hiddenPlatformModule), emptySet())).isTrue()
        assertThat(isForbiddenCoordinate(checkNotNull(allowedJvmModule), emptySet())).isFalse()
    }

    @Test
    fun `version catalog alias normalization matches generated accessor`() {
        assertThat(normalizeCatalogAlias("paging-common")).isEqualTo("paging.common")
        assertThat(normalizeCatalogAlias("androidx_test-core")).isEqualTo("androidx.test.core")
    }

    @Test
    fun `raw android imports are forbidden in pure contracts`() {
        assertThat(isForbiddenImport("android.net.Uri", emptySet())).isTrue()
        assertThat(isForbiddenImport("androidx.compose.runtime.Composable", emptySet())).isTrue()
        assertThat(isForbiddenImport("kotlinx.coroutines.flow.Flow", emptySet())).isFalse()
    }

    @Test
    fun `paging exception is exact and cannot admit paging runtime or compose`() {
        val imports = allowedImports.getValue("catalog/api")
        val modules = allowedModules.getValue("catalog/api")

        assertThat(isForbiddenImport("androidx.paging.PagingData", imports)).isFalse()
        assertThat(isForbiddenImport("androidx.paging.PagingSource", imports)).isTrue()
        assertThat(isForbiddenImport("androidx.paging.compose.LazyPagingItems", imports)).isTrue()
        assertThat(isForbiddenImport("androidx.room.Room", imports)).isTrue()

        assertThat(isForbiddenCoordinate("androidx.paging:paging-common:3.5.0", modules)).isFalse()
        assertThat(isForbiddenCoordinate("androidx.paging:paging-runtime:3.5.0", modules)).isTrue()
        assertThat(isForbiddenCoordinate("androidx.paging:paging-compose:3.5.0", modules)).isTrue()
        assertThat(isForbiddenCoordinate("androidx.room:room-runtime:3.0.0", modules)).isTrue()
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
        dependencyDeclarations(module).mapNotNull(::parseLibAccessor)

    private fun declaredProjectModules(module: String): List<String> =
        dependencyDeclarations(module).mapNotNull(::parseProjectModule)

    private fun declaredCoordinates(module: String): List<String> =
        dependencyDeclarations(module)
            .filterNot { declaration -> PROJECT_MODULE_REGEX.containsMatchIn(declaration) }
            .mapNotNull(::parseStringCoordinate)

    private fun dependencyDeclarations(module: String): List<String> =
        root.resolve(module).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .flatMap { file -> parseDependencyDeclarations(file.readText()).asSequence() }
            .toList()

    /**
     * Extracts complete dependency calls while respecting nested parentheses and quoted strings.
     * The declaration start must be at the beginning of a Gradle line (ignoring whitespace), so
     * commented prose and string literals cannot manufacture a dependency declaration.
     */
    private fun parseDependencyDeclarations(text: String): List<String> {
        val declarations = mutableListOf<String>()
        DEPENDENCY_DECLARATION_START_REGEX.findAll(text).forEach { match ->
            val openParen = text.indexOf('(', startIndex = match.range.first)
            if (openParen < 0) return@forEach

            var depth = 0
            var quote: Char? = null
            var escaped = false
            var index = openParen
            while (index < text.length) {
                val char = text[index]
                if (quote != null) {
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == quote) {
                        quote = null
                    }
                } else {
                    when (char) {
                        '"', '\'' -> quote = char
                        '(' -> depth += 1
                        ')' -> {
                            depth -= 1
                            if (depth == 0) {
                                declarations += text.substring(match.range.first, index + 1)
                                break
                            }
                        }
                    }
                }
                index += 1
            }
        }
        return declarations
    }

    private fun parseLibAccessor(declaration: String): String? =
        LIBS_ACCESSOR_REGEX.find(declaration)?.groupValues?.get(1)

    private fun parseProjectModule(declaration: String): String? =
        PROJECT_MODULE_REGEX.find(declaration)?.groupValues?.get(1)

    private fun parseStringCoordinate(declaration: String): String? =
        STRING_COORDINATE_REGEX.find(declaration)?.groupValues?.get(2)

    private fun readVersionCatalogModules(): Map<String, String> {
        val catalog = root.resolve("gradle/libs.versions.toml").readLines()
        var inLibraries = false
        val modules = linkedMapOf<String, String>()
        catalog.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inLibraries = line == "[libraries]"
            } else if (inLibraries && line.isNotEmpty() && !line.startsWith("#")) {
                VERSION_CATALOG_MODULE_REGEX.find(line)?.let { match ->
                    val alias = normalizeCatalogAlias(match.groupValues[1])
                    modules[alias] = match.groupValues[2]
                }
            }
        }
        return modules
    }

    private fun normalizeCatalogAlias(alias: String): String =
        alias.replace('-', '.').replace('_', '.')

    private fun resolveCatalogModule(accessor: String, catalog: Map<String, String>): String? =
        catalog[accessor]

    private fun isForbiddenImport(importFqcn: String, allowed: Set<String>): Boolean =
        importFqcn !in allowed && forbiddenImportPrefixes.any { importFqcn.startsWith(it) }

    private fun isForbiddenProjectModule(projectModule: String): Boolean =
        forbiddenProjectModules.any { projectModule.startsWith(it) }

    private fun isForbiddenCoordinate(coordinate: String, allowedModules: Set<String>): Boolean {
        val module = coordinate.split(':').take(2).joinToString(":")
        if (module in allowedModules) return false
        return forbiddenModulePrefixes.any { module.startsWith(it) } ||
            forbiddenModuleFragments.any { module.contains(it) }
    }

    private companion object {
        val IMPORT_REGEX = Regex("^import\\s+([A-Za-z0-9_.]+)")
        val LIBS_ACCESSOR_REGEX = Regex("""libs\.([a-zA-Z0-9.]+)""")
        val PROJECT_MODULE_REGEX =
            Regex("""project\(\s*(?:path\s*=\s*)?"(:[a-zA-Z0-9-:]+)"(?:\s*,)?\s*\)""")
        val STRING_COORDINATE_REGEX = Regex("""(["'])([a-zA-Z0-9_.:-]+)\1""")
        val DEPENDENCY_DECLARATION_START_REGEX = Regex(
            """(?m)^\s*(?:api|implementation|compileOnly|runtimeOnly|kapt|ksp|annotationProcessor)\s*\(""",
        )
        val VERSION_CATALOG_MODULE_REGEX = Regex(
            """^([A-Za-z0-9_.-]+)\s*=\s*\{[^}]*\bmodule\s*=\s*"([^"]+)"""",
        )
    }
}
