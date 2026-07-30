package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class MeasurementSeriesCommandFailureContractTest {
    @Test
    fun `malformed request fields return input error instead of internal error`() {
        val root = Files.createTempDirectory("muxtv-series-malformed")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        val request = root.resolve("request.json")
        Files.writeString(
            request,
            """
                {
                  "schemaVersion": "1",
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
        assertThat(result.stderr).isEqualTo("input-error\n")
        assertThat(result.stdout).isEmpty()
    }

    @Test
    fun `invalid operating system paths return usage error instead of internal error`() {
        val result = runCommand(
            arrayOf(
                "--request", "invalid\u0000request",
                "--input-directory", "input",
                "--output-directory", "output",
            ),
        )

        assertThat(result.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.USAGE.code)
        assertThat(result.stderr).isEqualTo("usage-error\n")
        assertThat(result.stdout).isEmpty()
    }

    private fun runCommand(args: Array<String>): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exitCode = MeasurementSeriesCommand.run(args, stdout, stderr)
        return CommandResult(exitCode = exitCode, stdout = stdout.toString(), stderr = stderr.toString())
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
