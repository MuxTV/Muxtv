package app.muxtv.testing

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ModuleDependencyRulesTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `pure contract modules do not reference platform libraries`() {
        val forbidden = listOf("androidx.", "com.android.", "Media3", "ExoPlayer", "Room", "OkHttp", "Hilt", "Compose")
        listOf("core/model", "catalog/api", "player/api").forEach { module ->
            val text = root.resolve(module).walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.name.endsWith(".gradle.kts")) }
                .joinToString("\n") { it.readText() }
            forbidden.forEach { token -> assertThat(text).doesNotContain(token) }
        }
    }

    @Test
    fun `feature does not depend on database or media3 implementation`() {
        val build = root.resolve("feature/home/build.gradle.kts").readText()
        assertThat(build).doesNotContain(":core:database")
        assertThat(build).doesNotContain(":player:media3")
    }
}
