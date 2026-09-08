package app.muxtv.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.benchmark.competitive.CompetitiveAggregator
import app.muxtv.benchmark.competitive.CompetitiveCorrectness
import app.muxtv.benchmark.competitive.CompetitiveDriverKind
import app.muxtv.benchmark.competitive.CompetitiveEnvironment
import app.muxtv.benchmark.competitive.CompetitiveEvidenceRef
import app.muxtv.benchmark.competitive.CompetitiveMetric
import app.muxtv.benchmark.competitive.CompetitiveReport
import app.muxtv.benchmark.competitive.CompetitiveScenario
import app.muxtv.benchmark.competitive.CompetitiveScenarioVariant
import app.muxtv.benchmark.competitive.CompetitiveVariant
import app.muxtv.benchmark.competitive.CompetitiveVariantResult
import app.muxtv.benchmark.competitive.CorpusProvenance
import app.muxtv.benchmark.competitive.RepositoryPin
import app.muxtv.database.measurement.RefreshDeltaDatabaseMeasurementJsonWriter
import app.muxtv.database.measurement.RefreshDeltaDatabaseMeasurementReport
import app.muxtv.database.measurement.RefreshDeltaDatabaseMeasurementRunner
import app.muxtv.database.measurement.RefreshDeltaDatabaseMeasurementSpec
import app.muxtv.database.measurement.RefreshDeltaDatabaseVariant
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@CatalogDatabaseMeasurement
@RunWith(AndroidJUnit4::class)
class RefreshDeltaDatabaseMeasurementTest {
    @Test
    fun producesThresholdFreeFileBackedRoomAndSqliteEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val sourceCommit = requireNotNull(arguments.getString(ARG_SOURCE_COMMIT)) {
            "C03 source commit is required."
        }
        val warmups = arguments.getString(ARG_WARMUPS)?.toIntOrNull() ?: 1
        val iterations = arguments.getString(ARG_ITERATIONS)?.toIntOrNull() ?: 5

        val report = RefreshDeltaDatabaseMeasurementRunner(
            context = instrumentation.targetContext,
        ).run(
            RefreshDeltaDatabaseMeasurementSpec(
                sourceCommit = sourceCommit,
                warmupIterations = warmups,
                measuredIterations = iterations,
            ),
        )

        assertThat(report.thresholdApplied).isFalse()
        assertThat(report.redactionPassed).isTrue()
        assertThat(report.sourceCommit).isEqualTo(sourceCommit)
        assertThat(report.entryCount).isEqualTo(10_000)
        assertThat(report.scenarios.map { it.scenarioId }).containsExactly(
            "delta-0",
            "delta-1",
            "delta-10",
            "delta-100",
            "reorder",
            "remove-10",
        ).inOrder()

        val competitiveReports = report.scenarios.map { scenario ->
            assertThat(scenario.variants.map { it.variant }).containsExactly(
                RefreshDeltaDatabaseVariant.A_CURRENT_MUXTV,
                RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW,
            ).inOrder()
            scenario.variants.forEach { variant ->
                assertThat(variant.correctnessDigestSha256)
                    .isEqualTo(scenario.expectedActiveDigestSha256)
                assertThat(variant.correctnessCount).isEqualTo(scenario.expectedActiveCount)
                assertThat(variant.previousGoodCount).isEqualTo(report.entryCount)
                assertThat(variant.actualRowMutations).isGreaterThan(0L)
                assertThat(variant.samples).hasSize(iterations)
                assertThat(variant.samples.all { it.refreshWallNanos > 0L }).isTrue()
                assertThat(variant.samples.all { it.stageTotalNanos > 0L }).isTrue()
                assertThat(variant.samples.all { it.activationTransactionNanos > 0L }).isTrue()
            }
            scenario.toCompetitiveReport(report, warmups, iterations)
        }

        assertThat(competitiveReports.all { !it.thresholdApplied }).isTrue()
        val output = publishReport(report, competitiveReports)
        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(0L)
    }

    private fun app.muxtv.database.measurement.RefreshDeltaDatabaseScenarioReport.toCompetitiveReport(
        report: RefreshDeltaDatabaseMeasurementReport,
        warmups: Int,
        iterations: Int,
    ): CompetitiveReport {
        val repositoryPin = RepositoryPin(
            repository = "MuxTV/Muxtv",
            sha = report.sourceCommit,
        )
        val corpus = CorpusProvenance(
            manifestSha256 = report.fixtureManifestSha256,
            contentSha256 = corpusContentSha256,
        )
        val scenario = CompetitiveScenario(
            schemaVersion = 1,
            scenarioId = "c03-$scenarioId",
            seed = C03_SEED,
            warmupRounds = warmups,
            measuredRounds = iterations,
            baselineVariant = CompetitiveVariant.A,
            corpus = corpus,
            variants = listOf(
                CompetitiveScenarioVariant(
                    id = CompetitiveVariant.A,
                    label = "current-muxtv-room",
                    source = repositoryPin,
                    driverKind = CompetitiveDriverKind.EXTERNAL,
                    benchmarkRef = "c03-room-file-backed-a",
                ),
                CompetitiveScenarioVariant(
                    id = CompetitiveVariant.B,
                    label = "immutable-cow-sqlite",
                    source = repositoryPin,
                    driverKind = CompetitiveDriverKind.EXTERNAL,
                    benchmarkRef = "c03-sqlite-file-backed-b",
                ),
            ),
        )
        val environment = CompetitiveEnvironment(
            schemaVersion = 1,
            environmentId = "c03-api-${report.environment.apiLevel}",
            buildMode = "debug",
            baselineProfileState = "not-applicable",
            networkProfile = "offline",
            runtime = mapOf(
                "api-level" to report.environment.apiLevel.toString(),
                "processors" to report.environment.availableProcessors.toString(),
            ),
            device = mapOf(
                "manufacturer" to report.environment.manufacturer,
                "model" to report.environment.model,
            ),
        )
        val results = variants.map { variantReport ->
            val competitiveVariant = when (variantReport.variant) {
                RefreshDeltaDatabaseVariant.A_CURRENT_MUXTV -> CompetitiveVariant.A
                RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW -> CompetitiveVariant.B
            }
            CompetitiveVariantResult(
                schemaVersion = 1,
                scenarioId = scenario.scenarioId,
                variant = competitiveVariant,
                source = repositoryPin,
                corpus = corpus,
                environmentFingerprintSha256 = environment.fingerprintSha256,
                correctness = CompetitiveCorrectness(
                    passed = variantReport.correctnessDigestSha256 == expectedActiveDigestSha256 &&
                        variantReport.correctnessCount == expectedActiveCount &&
                        variantReport.previousGoodCount == report.entryCount,
                    digestSha256 = variantReport.correctnessDigestSha256,
                    resultCount = variantReport.correctnessCount.toLong(),
                ),
                metrics = listOf(
                    CompetitiveMetric(
                        metricId = "refresh-wall-ns",
                        unit = "ns",
                        samples = variantReport.samples.map { it.refreshWallNanos },
                    ),
                    CompetitiveMetric(
                        metricId = "stage-total-ns",
                        unit = "ns",
                        samples = variantReport.samples.map { it.stageTotalNanos },
                    ),
                    CompetitiveMetric(
                        metricId = "stage-max-transaction-ns",
                        unit = "ns",
                        samples = variantReport.samples.map { it.maxStageTransactionNanos },
                    ),
                    CompetitiveMetric(
                        metricId = "activation-transaction-ns",
                        unit = "ns",
                        samples = variantReport.samples.map { it.activationTransactionNanos },
                    ),
                    CompetitiveMetric(
                        metricId = "row-mutations",
                        unit = "rows",
                        samples = listOf(variantReport.actualRowMutations),
                    ),
                    CompetitiveMetric(
                        metricId = "wal-peak-bytes",
                        unit = "bytes",
                        samples = variantReport.samples.map { it.walPeakBytes.coerceAtLeast(1L) },
                    ),
                    CompetitiveMetric(
                        metricId = "checkpoint-db-bytes",
                        unit = "bytes",
                        samples = variantReport.samples.map { it.afterCheckpoint.databaseBytes.coerceAtLeast(1L) },
                    ),
                ),
                evidenceRefs = listOf(CompetitiveEvidenceRef(EVIDENCE_REF)),
                redactionPassed = report.redactionPassed,
            )
        }
        return CompetitiveAggregator.aggregate(scenario, environment, results)
    }

    private fun publishReport(
        report: RefreshDeltaDatabaseMeasurementReport,
        competitiveReports: List<CompetitiveReport>,
    ): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = requireNotNull(context.getExternalFilesDir(null))
        val directory = File(root, OUTPUT_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, OUTPUT_FILE)
        output.outputStream().use { stream ->
            RefreshDeltaDatabaseMeasurementJsonWriter.write(
                report = report,
                competitiveReports = competitiveReports,
                output = stream,
            )
        }
        return output
    }

    private companion object {
        const val ARG_SOURCE_COMMIT = "c03SourceCommit"
        const val ARG_WARMUPS = "c03Warmups"
        const val ARG_ITERATIONS = "c03Iterations"
        const val OUTPUT_DIRECTORY = "c03-room"
        const val OUTPUT_FILE = "c03-refresh-delta.json"
        const val EVIDENCE_REF = "c03-room/c03-refresh-delta.json"
        const val C03_SEED = 348_003L
    }
}
