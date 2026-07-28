package app.muxtv.testing.iptv

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

/** Repository-owned command boundary for deterministic corpus artifact generation. */
class M3uCorpusCommand private constructor(
    private val publishPair: (M3uCorpusArtifactRequest) -> M3uCorpusArtifactPair,
) {
    constructor() : this(M3uCorpusArtifactPublisher()::publish)

    fun run(
        args: List<String>,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        return when (val parsed = parseArguments(args)) {
            CorpusCommandParseResult.Help -> {
                stdout.writeLine(USAGE)
                EXIT_SUCCESS
            }

            CorpusCommandParseResult.Invalid -> {
                stderr.writeLine("Invalid corpus command arguments.")
                stderr.writeLine(USAGE)
                EXIT_USAGE
            }

            is CorpusCommandParseResult.Ready -> publish(parsed.request, stdout, stderr)
        }
    }

    private fun publish(
        request: M3uCorpusArtifactRequest,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        return try {
            val pair = publishPair(request)
            val manifest = pair.manifest

            stdout.writeLine("MuxTV corpus generated.")
            stdout.writeLine("profile=${manifest.profile.artifactId}")
            stdout.writeLine("seed=${manifest.seed}")
            stdout.writeLine("playlist=${pair.playlistPath.fileName}")
            stdout.writeLine("manifest=${pair.manifestPath.fileName}")
            stdout.writeLine("parsed=${manifest.expectedParsedEntries}")
            stdout.writeLine("skipped=${manifest.expectedSkippedEntries}")
            stdout.writeLine("warnings=${manifest.expectedWarningCount}")
            stdout.writeLine("duplicates=${manifest.expectedDuplicateIdentities}")
            stdout.writeLine("unique=${manifest.expectedUniqueIdentities}")
            stdout.writeLine("bytes=${manifest.utf8ByteCount}")
            stdout.writeLine("sha256=${manifest.sha256}")
            EXIT_SUCCESS
        } catch (error: M3uCorpusArtifactException) {
            stderr.writeLine(error.message ?: "Corpus artifact publication failed.")
            EXIT_PUBLISH
        } catch (_: Exception) {
            stderr.writeLine("Corpus generation failed.")
            EXIT_INTERNAL
        }
    }

    private fun parseArguments(args: List<String>): CorpusCommandParseResult {
        if (args == listOf("--help")) {
            return CorpusCommandParseResult.Help
        }
        if (args.isEmpty() || "--help" in args) {
            return CorpusCommandParseResult.Invalid
        }

        val values = linkedMapOf<String, String>()
        var overwrite = false
        var index = 0

        while (index < args.size) {
            val option = args[index]
            when {
                option == "--overwrite" -> {
                    if (overwrite) {
                        return CorpusCommandParseResult.Invalid
                    }
                    overwrite = true
                    index += 1
                }

                option in VALUE_OPTIONS -> {
                    if (values.containsKey(option)) {
                        return CorpusCommandParseResult.Invalid
                    }
                    val valueIndex = index + 1
                    if (valueIndex >= args.size || args[valueIndex].startsWith("--")) {
                        return CorpusCommandParseResult.Invalid
                    }
                    values[option] = args[valueIndex]
                    index += 2
                }

                else -> return CorpusCommandParseResult.Invalid
            }
        }

        if (!values.keys.containsAll(REQUIRED_VALUE_OPTIONS) || values.size != REQUIRED_VALUE_OPTIONS.size) {
            return CorpusCommandParseResult.Invalid
        }

        val profile = M3uCorpusProfile.entries.firstOrNull {
            it.artifactId == values.getValue("--profile")
        } ?: return CorpusCommandParseResult.Invalid
        val seed = values.getValue("--seed").toLongOrNull()
            ?: return CorpusCommandParseResult.Invalid
        val sourceCommit = values.getValue("--source-commit")
        val outputValue = values.getValue("--output")
        if (outputValue.isBlank()) {
            return CorpusCommandParseResult.Invalid
        }

        val outputDirectory = try {
            Path.of(outputValue)
        } catch (_: InvalidPathException) {
            return CorpusCommandParseResult.Invalid
        }

        val spec = try {
            M3uCorpusSpec(
                profile = profile,
                seed = seed,
                sourceCommit = sourceCommit,
            )
        } catch (_: IllegalArgumentException) {
            return CorpusCommandParseResult.Invalid
        }

        return CorpusCommandParseResult.Ready(
            M3uCorpusArtifactRequest(
                spec = spec,
                outputDirectory = outputDirectory,
                overwrite = overwrite,
            ),
        )
    }

    internal companion object {
        const val EXIT_SUCCESS: Int = 0
        const val EXIT_USAGE: Int = 2
        const val EXIT_PUBLISH: Int = 3
        const val EXIT_INTERNAL: Int = 4

        private val VALUE_OPTIONS = setOf(
            "--profile",
            "--seed",
            "--source-commit",
            "--output",
        )
        private val REQUIRED_VALUE_OPTIONS = VALUE_OPTIONS
        private const val USAGE =
            "Usage: --profile <small-1k|medium-10k|large-50k> --seed <long> " +
                "--source-commit <40-char-lowercase-sha> --output <directory> [--overwrite]"

        fun forTesting(
            publishPair: (M3uCorpusArtifactRequest) -> M3uCorpusArtifactPair,
        ): M3uCorpusCommand = M3uCorpusCommand(publishPair)
    }
}

private sealed interface CorpusCommandParseResult {
    data object Help : CorpusCommandParseResult

    data object Invalid : CorpusCommandParseResult

    data class Ready(
        val request: M3uCorpusArtifactRequest,
    ) : CorpusCommandParseResult
}

private fun Appendable.writeLine(value: String) {
    append(value)
    append('\n')
}

fun main(args: Array<String>) {
    val exitCode = M3uCorpusCommand().run(
        args = args.toList(),
        stdout = System.out,
        stderr = System.err,
    )
    if (exitCode != M3uCorpusCommand.EXIT_SUCCESS) {
        exitProcess(exitCode)
    }
}
