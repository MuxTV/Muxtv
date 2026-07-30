package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class MeasurementSeriesCommandClassificationTest {
    @Test
    fun `invalid operating-system paths are usage errors rather than internal failures`() {
        val result = runCommand(
            arrayOf(
                "--request", "invalid\u0000request.json",
                "--input-directory", "input",
                "--output-directory", "output",
            ),
        )

        assertThat(result.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.USAGE.code)
        assertThat(result.stderr).contains("usage-error")
        assertThat(result.stderr).doesNotContain("internal-error")
    }

    @Test
    fun `malformed typed request fields are input errors rather than internal failures`() {
        val root = Files.createTempDirectory("muxtv-series-classification")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        val request = root.resolve("request.json")
        Files.writeString(
            request,
            """
            {
              "schemaVersion": "one",
              "family": "m3u-parse",
              "outputName": "variance.json",
              "runs": [],
              "androidProfile": null
            }
            """.trimIndent(),
        )

        val result = runCommand(
            arrayOf(
                "--request", request.toString(),
                "--input-directory", input.toString(),
                "--output-directory", output.toString(),
            ),
        )

        assertThat(result.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.INPUT.code)
        assertThat(result.stderr).contains("input-error")
        assertThat(result.stderr).doesNotContain("internal-error")
    }

    private fun runCommand(args: Array<String>): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exitCode = MeasurementSeriesCommand.run(args, stdout, stderr)
        return CommandResult(exitCode, stdout.toString(), stderr.toString())
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
