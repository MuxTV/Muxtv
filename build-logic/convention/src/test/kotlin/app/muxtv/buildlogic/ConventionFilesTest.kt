package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionFilesTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `version catalog contains the approved Phase 00 aliases`() {
        val catalog = repositoryRoot.resolve("gradle/libs.versions.toml").readText()
        listOf(
            "agp = \"9.3.0\"",
            "kotlin = \"2.4.10\"",
            "compose-bom = \"2026.06.00\"",
            "tv-material = \"1.1.0\"",
            "tv-foundation = \"1.0.0\"",
            "navigation3 = \"1.1.7\"",
            "media3 = \"1.11.0\"",
            "room3 = \"3.0.0\"",
            "dagger-hilt = \"2.60.1\"",
            "work = \"2.11.2\"",
            "datastore = \"1.2.1\"",
            "tracing = \"2.0.1\"",
        ).forEach { expected -> assertContains(catalog, expected) }
    }

    @Test
    fun `three convention plugin sources exist`() {
        val sourceRoot = repositoryRoot.resolve("build-logic/convention/src/main/kotlin/app/muxtv/buildlogic")
        listOf(
            "AndroidApplicationConventionPlugin.kt",
            "AndroidLibraryConventionPlugin.kt",
            "KotlinLibraryConventionPlugin.kt",
        ).forEach { name ->
            assertTrue(sourceRoot.resolve(name).isFile, "Missing convention plugin source: $name")
        }
    }

    @Test
    fun `tracing instruments accepted search and player adapter boundaries`() {
        val searchRepository = repositoryRoot.resolve(
            "core/database/src/main/kotlin/app/muxtv/database/RoomChannelSearchRepository.kt",
        ).readText()
        val playbackService = repositoryRoot.resolve(
            "player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt",
        ).readText()

        assertContains(
            searchRepository,
            "MuxTvTrace.global.coroutineSection(MuxTvTraceSection.SEARCH) {",
        )
        assertEquals(
            2,
            playbackService.countLiteral(
                "MuxTvTrace.global.section(MuxTvTraceSection.PLAYER_PREPARE) {",
            ),
            "Internal and external player prepare paths must share the same bounded trace taxonomy.",
        )
        assertEquals(
            2,
            playbackService.countLiteral(
                "MuxTvTrace.global.section(MuxTvTraceSection.FIRST_FRAME) {",
            ),
            "Internal and external first-frame callbacks must emit the same bounded trace slice.",
        )
    }
}

private fun String.countLiteral(value: String): Int =
    windowed(value.length, step = 1, partialWindows = false).count { it == value }
