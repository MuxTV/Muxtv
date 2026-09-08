package app.muxtv.benchmark.competitive

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CompetitiveBenchmarkLabTest {
    @Test
    fun `performance plan is deterministic interleaved and starts only after correctness passes`() {
        val scenario = scenario(seed = 741L, warmupRounds = 2, measuredRounds = 3)
        val correctness = scenario.variants.associate { variant -> variant.id to true }

        val first = CompetitiveExecutionPlanner.planPerformance(scenario, correctness)
        val second = CompetitiveExecutionPlanner.planPerformance(scenario, correctness)

        assertThat(first).isEqualTo(second)
        assertThat(first.takeWhile { it.phase == CompetitiveExecutionPhase.WARMUP }).hasSize(6)
        assertThat(first.drop(6)).hasSize(9)
        assertThat(first.take(6).all { it.phase == CompetitiveExecutionPhase.WARMUP }).isTrue()
        assertThat(first.drop(6).all { it.phase == CompetitiveExecutionPhase.MEASURED }).isTrue()
        first.groupBy { it.phase to it.round }.values.forEach { round ->
            assertThat(round.map(CompetitiveRunSlot::variant).toSet())
                .containsExactly(CompetitiveVariant.A, CompetitiveVariant.B, CompetitiveVariant.C)
        }
    }

    @Test
    fun `performance plan rejects any failed or missing correctness result`() {
        val scenario = scenario()

        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveExecutionPlanner.planPerformance(
                scenario,
                mapOf(
                    CompetitiveVariant.A to true,
                    CompetitiveVariant.B to false,
                    CompetitiveVariant.C to true,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveExecutionPlanner.planPerformance(
                scenario,
                mapOf(CompetitiveVariant.A to true, CompetitiveVariant.B to true),
            )
        }
    }

    @Test
    fun `environment fingerprint is stable across metadata ordering and changes when a stable field changes`() {
        val first = environment(runtime = linkedMapOf("jdk" to "25", "os" to "linux"))
        val reordered = environment(runtime = linkedMapOf("os" to "linux", "jdk" to "25"))
        val changed = environment(runtime = linkedMapOf("os" to "linux", "jdk" to "26"))

        assertThat(first.fingerprintSha256).isEqualTo(reordered.fingerprintSha256)
        assertThat(first.fingerprintSha256).isNotEqualTo(changed.fingerprintSha256)
    }

    @Test
    fun `source corpus and evidence provenance reject invalid or unsafe values`() {
        assertThrows(IllegalArgumentException::class.java) {
            RepositoryPin("MuxTV/Muxtv", "main")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CorpusProvenance(SHA256, "not-a-sha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveEvidenceRef("C:\\Users\\runner\\trace.perfetto-trace")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveEvidenceRef("../secret.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveEvidenceRef("reports/result.json?access_token=secret")
        }

        assertThat(CompetitiveEvidenceRef("reports/run-001/result.json").value)
            .isEqualTo("reports/run-001/result.json")
    }

    @Test
    fun `aggregation refuses performance when correctness redaction or provenance is invalid`() {
        val scenario = scenario()
        val environment = environment()
        val valid = scenario.variants.map { variant -> result(scenario, environment, variant) }

        val report = CompetitiveAggregator.aggregate(scenario, environment, valid)
        assertThat(report.variantReports).hasSize(3)
        assertThat(report.variantReports.first { it.variant == CompetitiveVariant.B }
            .metrics.single().deltaBasisPointsFromBaseline).isEqualTo(1_000L)

        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveAggregator.aggregate(
                scenario,
                environment,
                valid.map { value ->
                    if (value.variant == CompetitiveVariant.B) value.copy(
                        correctness = value.correctness.copy(passed = false),
                    ) else value
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveAggregator.aggregate(
                scenario,
                environment,
                valid.map { value ->
                    if (value.variant == CompetitiveVariant.B) value.copy(redactionPassed = false) else value
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitiveAggregator.aggregate(
                scenario,
                environment,
                valid.map { value ->
                    if (value.variant == CompetitiveVariant.B) value.copy(
                        corpus = value.corpus.copy(contentSha256 = OTHER_SHA256),
                    ) else value
                },
            )
        }
    }

    @Test
    fun `normalized report uses measured samples only and nearest-rank percentiles`() {
        val scenario = scenario()
        val environment = environment()
        val results = scenario.variants.map { variant ->
            result(
                scenario = scenario,
                environment = environment,
                variant = variant,
                samples = when (variant.id) {
                    CompetitiveVariant.A -> listOf(10L, 20L, 30L, 40L, 50L)
                    CompetitiveVariant.B -> listOf(11L, 22L, 33L, 44L, 55L)
                    CompetitiveVariant.C -> listOf(9L, 18L, 27L, 36L, 45L)
                    CompetitiveVariant.D -> error("not used")
                },
            )
        }

        val baseline = CompetitiveAggregator.aggregate(scenario, environment, results)
            .variantReports.first { it.variant == CompetitiveVariant.A }
            .metrics.single()

        assertThat(baseline.sampleCount).isEqualTo(5)
        assertThat(baseline.median).isEqualTo(30L)
        assertThat(baseline.p90).isEqualTo(50L)
        assertThat(baseline.p95).isEqualTo(50L)
        assertThat(baseline.p99).isEqualTo(50L)
    }

    private fun scenario(
        seed: Long = 1234L,
        warmupRounds: Int = 1,
        measuredRounds: Int = 5,
    ) = CompetitiveScenario(
        schemaVersion = 1,
        scenarioId = "c01-contract-smoke",
        seed = seed,
        warmupRounds = warmupRounds,
        measuredRounds = measuredRounds,
        baselineVariant = CompetitiveVariant.A,
        corpus = CorpusProvenance(SHA256, CONTENT_SHA256),
        variants = listOf(
            CompetitiveScenarioVariant(
                CompetitiveVariant.A,
                "MuxTV current",
                RepositoryPin("MuxTV/Muxtv", MUX_SHA),
                CompetitiveDriverKind.JMH,
                "app.muxtv.benchmark.StreamingParserBenchmark",
            ),
            CompetitiveScenarioVariant(
                CompetitiveVariant.B,
                "MuxTV candidate",
                RepositoryPin("MuxTV/Muxtv", CANDIDATE_SHA),
                CompetitiveDriverKind.JMH,
                "app.muxtv.benchmark.StreamingParserBenchmark",
            ),
            CompetitiveScenarioVariant(
                CompetitiveVariant.C,
                "OwnTV reference",
                RepositoryPin("ahXN00/OwnTV", OWNTV_SHA),
                CompetitiveDriverKind.EXTERNAL,
                "core/src/main/java/tv/own/owntv/core/parser/M3uParser.kt",
            ),
        ),
    )

    private fun environment(runtime: Map<String, String> = mapOf("os" to "linux", "jdk" to "25")) =
        CompetitiveEnvironment(
            schemaVersion = 1,
            environmentId = "hosted-smoke",
            buildMode = "benchmark",
            baselineProfileState = "not-applicable",
            networkProfile = "local-deterministic",
            runtime = runtime,
            device = emptyMap(),
        )

    private fun result(
        scenario: CompetitiveScenario,
        environment: CompetitiveEnvironment,
        variant: CompetitiveScenarioVariant,
        samples: List<Long> = when (variant.id) {
            CompetitiveVariant.A -> listOf(90L, 100L, 110L)
            CompetitiveVariant.B -> listOf(99L, 110L, 121L)
            CompetitiveVariant.C -> listOf(81L, 90L, 99L)
            CompetitiveVariant.D -> error("not used")
        },
    ) = CompetitiveVariantResult(
        schemaVersion = 1,
        scenarioId = scenario.scenarioId,
        variant = variant.id,
        source = variant.source,
        corpus = scenario.corpus,
        environmentFingerprintSha256 = environment.fingerprintSha256,
        correctness = CompetitiveCorrectness(
            passed = true,
            digestSha256 = SHA256,
            resultCount = 1_000L,
        ),
        metrics = listOf(CompetitiveMetric("wall-time", "ns", samples)),
        evidenceRefs = listOf(CompetitiveEvidenceRef("reports/${variant.id.name.lowercase()}/result.json")),
        redactionPassed = true,
    )

    companion object {
        private const val MUX_SHA = "9daac297ef4e2c7748e243fc3e912142bbbc6076"
        private const val CANDIDATE_SHA = "1111111111111111111111111111111111111111"
        private const val OWNTV_SHA = "b70af186731fa860da2b99aa05ead5d77f4da927"
        private const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val CONTENT_SHA256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OTHER_SHA256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
