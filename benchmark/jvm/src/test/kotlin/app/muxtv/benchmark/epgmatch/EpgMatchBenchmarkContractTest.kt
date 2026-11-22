package app.muxtv.benchmark.epgmatch

import app.muxtv.benchmark.competitive.CompetitiveDriverKind
import app.muxtv.benchmark.competitive.CompetitiveVariant
import app.muxtv.benchmark.epg.HybridEpgMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgMatchBenchmarkContractTest {
    @Test
    fun adversarialCorpusCoversRequiredSafetyAndUnicodeClasses() {
        val cases = EpgMatchAdversarialCorpus.cases
        val caseIds = cases.map { it.id }.toSet()
        val tags = cases.flatMapTo(linkedSetOf()) { it.tags }

        assertThat(caseIds).containsAtLeast(
            "sky-sports-2-vs-3",
            "mtv-vs-mtv-2",
            "bbc-one-vs-1",
            "unicode-arabic-indic-digit",
            "cyrillic-quality-noise",
            "greek-quality-noise",
            "cjk-quality-noise",
            "regional-fr-mtv",
            "timeshift-plus-1",
        )
        assertThat(tags).containsAtLeast(
            "latin",
            "cyrillic",
            "greek",
            "cjk",
            "unicode-digits",
            "regional",
            "timeshift",
            "ambiguous",
            "hard-negative",
        )
        assertThat(cases.map { it.id }.distinct()).hasSize(cases.size)
        assertThat(EpgMatchAdversarialCorpus.sha256).matches("[0-9a-f]{64}")
        assertThat(EpgMatchAdversarialCorpus.manifestSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun variantAPreservesCurrentExactFirstContractAndProviderIsolation() {
        val evaluation = EpgMatchEvaluator.evaluate(
            variant = EpgBenchmarkVariant.A_CURRENT_EXACT,
            corpus = EpgMatchAdversarialCorpus,
        )

        assertThat(evaluation.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(evaluation.outcome("trusted-id-wins").autoCanonicalId).isEqualTo("p1-bbc-one")
        assertThat(evaluation.outcome("duplicate-world-news").decision)
            .isEqualTo(EpgBenchmarkDecision.AMBIGUOUS)
        assertThat(evaluation.outcome("provider-isolation").autoCanonicalId).isEqualTo("p1-cafe-tv")
        assertThat(evaluation.outcome("bbc-one-vs-1").decision)
            .isEqualTo(EpgBenchmarkDecision.UNRESOLVED)
    }

    @Test
    fun thresholdSweepSelectsOnlyZeroFalseHybridAndImprovesRecall() {
        val baseline = EpgMatchEvaluator.evaluate(
            variant = EpgBenchmarkVariant.A_CURRENT_EXACT,
            corpus = EpgMatchAdversarialCorpus,
        )
        val sweep = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus)
        val selected = sweep.selectedEvaluation

        assertThat(sweep.autoThresholds.size).isAtLeast(10)
        assertThat(sweep.autoMargins.size).isAtLeast(5)
        assertThat(sweep.evaluatedConfigurations).isAtLeast(200)
        assertThat(selected.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(selected.metrics.autoRecall).isGreaterThan(baseline.metrics.autoRecall)
        assertThat(selected.metrics.autoPrecision).isEqualTo(1.0)
        assertThat(selected.metrics.meanReciprocalRank)
            .isAtLeast(baseline.metrics.meanReciprocalRank)
        assertThat(selected.metrics.top3HitRate).isAtLeast(baseline.metrics.top3HitRate)
        assertThat(sweep.selectedThresholds.autoThreshold).isIn(sweep.autoThresholds)
        assertThat(sweep.selectedThresholds.autoMargin).isIn(sweep.autoMargins)
    }

    @Test
    fun selectedHybridKeepsMandatoryAdversarialCasesSafe() {
        val evaluation = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus).selectedEvaluation

        assertThat(evaluation.outcome("sky-sports-2-vs-3").autoCanonicalId)
            .isEqualTo("p1-sky-sports-2")
        assertThat(evaluation.outcome("sky-sports-4-negative").autoCanonicalId).isNull()
        assertThat(evaluation.outcome("mtv-vs-mtv-2").autoCanonicalId).isEqualTo("p1-mtv")
        assertThat(evaluation.outcome("mtv-2").autoCanonicalId).isEqualTo("p1-mtv-2")
        assertThat(evaluation.outcome("bbc-one-vs-1").autoCanonicalId).isEqualTo("p1-bbc-one")
        assertThat(evaluation.outcome("unicode-arabic-indic-digit").autoCanonicalId)
            .isEqualTo("p1-arabic-2")
        assertThat(evaluation.outcome("unicode-arabic-indic-mismatch").autoCanonicalId).isNull()
        assertThat(evaluation.outcome("cyrillic-quality-noise").autoCanonicalId)
            .isEqualTo("p1-kinopremiera")
        assertThat(evaluation.outcome("greek-quality-noise").autoCanonicalId)
            .isEqualTo("p1-ert-1")
        assertThat(evaluation.outcome("cjk-quality-noise").autoCanonicalId)
            .isEqualTo("p1-cctv")
        assertThat(evaluation.outcome("regional-fr-mtv").autoCanonicalId).isEqualTo("p1-mtv-fr")
        assertThat(evaluation.outcome("regional-unknown-mtv").autoCanonicalId).isNull()
        assertThat(evaluation.outcome("timeshift-plus-1").autoCanonicalId)
            .isEqualTo("p1-discovery-plus-1")
        assertThat(evaluation.outcome("timeshift-plus-2-negative").autoCanonicalId).isNull()
    }

    @Test
    fun ownTvReferenceIsMeasuredAsReferenceNotUsedToSelectBThresholds() {
        val sweep = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus)
        val reference = EpgMatchEvaluator.evaluate(
            variant = EpgBenchmarkVariant.C_OWNTV_REFERENCE,
            corpus = EpgMatchAdversarialCorpus,
        )

        assertThat(reference.metrics.totalCases).isEqualTo(EpgMatchAdversarialCorpus.cases.size)
        assertThat(sweep.selectionSource).isEqualTo(EpgThresholdSelectionSource.CORPUS_SWEEP)
        assertThat(sweep.referenceAutoThreshold).isEqualTo(0.92)
        assertThat(sweep.referenceReviewThreshold).isEqualTo(0.74)
    }

    @Test
    fun c01CompetitiveProvenanceContractIsReused() {
        val candidateSha = "abcdef0123456789abcdef0123456789abcdef01"
        val scenario = EpgCompetitiveContract.scenario(candidateSha)

        assertThat(scenario.corpus.contentSha256).isEqualTo(EpgMatchAdversarialCorpus.sha256)
        assertThat(scenario.corpus.manifestSha256).isEqualTo(EpgMatchAdversarialCorpus.manifestSha256)
        assertThat(scenario.baselineVariant).isEqualTo(CompetitiveVariant.A)
        assertThat(scenario.variants.map { it.id }).containsExactly(
            CompetitiveVariant.A,
            CompetitiveVariant.B,
            CompetitiveVariant.C,
        ).inOrder()
        assertThat(scenario.variants.single { it.id == CompetitiveVariant.A }.source.sha)
            .isEqualTo(EpgCompetitiveContract.MUXTV_C01_MAIN_SHA)
        assertThat(scenario.variants.single { it.id == CompetitiveVariant.B }.source.sha)
            .isEqualTo(candidateSha)
        assertThat(scenario.variants.single { it.id == CompetitiveVariant.C }.source.sha)
            .isEqualTo(EpgCompetitiveContract.OWNTV_CORE_SHA)
        assertThat(scenario.variants.all { it.driverKind == CompetitiveDriverKind.JMH }).isTrue()
    }

    @Test
    fun hybridNormalizationCacheRemainsBounded() {
        val case = EpgMatchAdversarialCorpus.cases.single { it.id == "bbc-one-vs-1" }
        val matcher = HybridEpgMatcher(
            candidates = case.candidates,
            providerSourceId = case.query.providerSourceId,
            normalizationCacheMaxEntries = 4,
        )

        repeat(32) { index ->
            matcher.match(case.query.copy(epgDisplayName = "BBC One HD $index"))
        }

        assertThat(matcher.normalizationCacheEntries()).isAtMost(4)
    }

    @Test
    fun evaluationAndThresholdSelectionAreDeterministic() {
        val first = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus)
        val second = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus)

        assertThat(second.selectedThresholds).isEqualTo(first.selectedThresholds)
        assertThat(second.selectedEvaluation.metrics).isEqualTo(first.selectedEvaluation.metrics)
        assertThat(second.selectedEvaluation.outcomes).isEqualTo(first.selectedEvaluation.outcomes)
        assertThat(second.reportDigest).isEqualTo(first.reportDigest)
    }
}
