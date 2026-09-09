package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlphaReleaseNotesContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release publishes bounded notes limitations recovery and evidence boundaries`() {
        val appBuild = repositoryRoot.resolve("app/tv/build.gradle.kts").readText()
        assertContains(appBuild, "versionName = \"0.1.0-alpha.1\"")

        val notes = repositoryRoot.resolve("docs/release/0.1.0-alpha.1.md")
        assertTrue(notes.isFile, "Missing 0.1.0-alpha.1 release notes.")
        val text = notes.readText()

        listOf(
            "# MuxTV 0.1.0-alpha.1",
            "## Alpha scope",
            "## Known limitations",
            "## Recovery",
            "## Evidence boundaries",
            "Live TV",
            "API26",
            "API36",
            "physical TV",
            "Fire TV",
            "Baseline Profile",
            "LIMITED_EVIDENCE",
            "release signing",
            "Doctor",
            "source refresh",
            "clear app data",
            "provider secrets",
            "#31",
        ).forEach { token -> assertContains(text, token) }

        assertFalse(
            Regex("(?i)(username|password|token|authorization)\\s*[=:]\\s*[^`\\s]+")
                .containsMatchIn(text),
            "Release notes must not embed provider credentials or authorization values.",
        )
        assertFalse(
            text.contains("all Android TV devices are supported", ignoreCase = true),
            "Alpha notes must not make an unbounded Android TV compatibility claim.",
        )
        assertFalse(
            text.contains("Baseline Profile improves startup", ignoreCase = true),
            "Hosted-emulator Baseline Profile evidence does not justify a material performance claim.",
        )
    }
}
