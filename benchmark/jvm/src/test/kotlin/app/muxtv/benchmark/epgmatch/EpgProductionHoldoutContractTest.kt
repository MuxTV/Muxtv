package app.muxtv.benchmark.epgmatch

import app.muxtv.benchmark.epg.EpgBenchmarkCandidate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgProductionHoldoutContractTest {
    @Test
    fun frozenC06CorpusRemainsUnchanged() {
        assertThat(EpgMatchAdversarialCorpus.sha256)
            .isEqualTo("ff3518e2cd0367ce877ba1bf1963eb0c1879581538155904500042c66447de77")
        assertThat(EpgMatchAdversarialCorpus.cases).hasSize(35)
    }

    @Test
    fun calibrationAndHoldoutAreIndependentDenseCompatibilitySets() {
        val calibration = EpgProductionValidationCorpora.calibration
        val holdout = EpgProductionValidationCorpora.holdout

        assertThat(calibration.cases).isNotEmpty()
        assertThat(holdout.cases).isNotEmpty()
        assertThat(calibration.partition).isEqualTo(EpgProductionDatasetPartition.CALIBRATION)
        assertThat(holdout.partition).isEqualTo(EpgProductionDatasetPartition.HOLDOUT)
        assertThat(calibration.familyIds.intersect(holdout.familyIds)).isEmpty()
        assertThat(calibration.sourceBuckets.intersect(holdout.sourceBuckets)).isEmpty()
        assertThat(calibration.cases.minOf { it.candidates.size }).isAtLeast(8)
        assertThat(holdout.cases.minOf { it.candidates.size }).isAtLeast(8)
        assertThat(calibration.cases.maxOf { it.candidates.size }).isAtMost(EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES)
        assertThat(holdout.cases.maxOf { it.candidates.size }).isAtMost(EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES)
        assertThat(calibration.sha256).isNotEqualTo(holdout.sha256)
        assertThat(calibration.seedPartitionSha256).isNotEqualTo(holdout.seedPartitionSha256)
    }

    @Test
    fun holdoutCoversMandatoryProductionRiskBuckets() {
        val tags = EpgProductionValidationCorpora.holdout.cases.flatMapTo(linkedSetOf()) { it.tags }

        assertThat(tags).containsAtLeastElementsIn(
            listOf(
                "near-duplicate",
                "numeric-sibling",
                "timeshift",
                "plus-1",
                "plus-2",
                "regional",
                "unicode-digits",
                "cyrillic",
                "greek",
                "cjk",
                "nfkc",
                "quality",
                "ambiguous",
                "duplicate",
                "isolation",
                "hard-negative",
            ),
        )
    }

    @Test
    fun calibrationCannotConsultHoldoutAndSelectionIsEvidenceBacked() {
        val corpus = EpgProductionValidationCorpora.calibration
        val calibration = EpgProductionThresholdCalibration.calibrate(corpus)

        assertThat(calibration.selectionSource).isEqualTo(EpgProductionThresholdSelectionSource.CALIBRATION_BREAKPOINT_SWEEP)
        assertThat(calibration.selectionInputPartition).isEqualTo(EpgProductionDatasetPartition.CALIBRATION)
        assertThat(calibration.calibrationSha256).isEqualTo(corpus.sha256)
        assertThat(calibration.candidateAutoThresholds).contains(calibration.selectedThresholds.autoThreshold)
        assertThat(calibration.candidateAutoMargins).contains(calibration.selectedThresholds.autoMargin)
        assertThat(calibration.evaluatedConfigurations).isGreaterThan(1)
        assertThat(calibration.selectedEvaluation.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(calibration.selectedEvaluation.metrics.autoRecall)
            .isAtLeast(EpgProductionPolicyContract.MIN_CALIBRATION_AUTO_RECALL)
        assertThat(calibration.calibrationFuzzyAutomaticDecisions)
            .isAtLeast(EpgProductionPolicyContract.MIN_CALIBRATION_FUZZY_AUTOMATIC_DECISIONS)
        assertThat(calibration.selectionDigestSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun selectedPolicyMustPassFrozenC06AndIndependentHoldoutWithoutFalseAuto() {
        val calibration = EpgProductionThresholdCalibration.calibrate(EpgProductionValidationCorpora.calibration)
        val gate = EpgProductionHoldoutGate.validate(
            calibration = calibration,
            frozenC06 = EpgMatchAdversarialCorpus,
            holdout = EpgProductionValidationCorpora.holdout,
        )

        assertThat(gate.frozenC06.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(gate.holdout.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(gate.holdoutFalseAutoByRiskBucket.values.toSet()).containsExactly(0)
        assertThat(gate.holdoutExactMissRecovery).isAtLeast(EpgProductionPolicyContract.MIN_HOLDOUT_EXACT_MISS_RECOVERY)
        assertThat(gate.automaticDecisionExposure).isAtLeast(EpgProductionPolicyContract.MIN_AUTOMATIC_DECISION_EXPOSURE)
        assertThat(gate.fuzzyAutomaticDecisionExposure)
            .isAtLeast(EpgProductionPolicyContract.MIN_FUZZY_AUTOMATIC_DECISION_EXPOSURE)
        assertThat(gate.exactSemanticRegressions).isEqualTo(0)
        assertThat(gate.manualOverridePrecedencePreserved).isTrue()
        assertThat(gate.passed).isTrue()
        assertThat(gate.disposition).isEqualTo(EpgProductionHoldoutDisposition.PASS)
    }

    @Test
    fun candidateOverflowFailsClosedAndCacheContractIsBounded() {
        val calibration = EpgProductionThresholdCalibration.calibrate(EpgProductionValidationCorpora.calibration)
        val template = EpgProductionValidationCorpora.calibration.cases.first()
        val overflow = template.copy(
            candidates = List(EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES + 1) { index ->
                EpgBenchmarkCandidate(
                    canonicalChannelId = "overflow-$index",
                    providerSourceId = template.query.providerSourceId,
                    tvgName = "Overflow $index",
                )
            },
        )

        val match = EpgProductionPolicy.evaluateCase(overflow, calibration.selectedThresholds)

        assertThat(match.decision).isEqualTo(EpgBenchmarkDecision.UNRESOLVED)
        assertThat(match.reason).isEqualTo(EpgBenchmarkReason.CANDIDATE_BUDGET_EXCEEDED)
        assertThat(EpgProductionPolicyContract.MAX_NORMALIZATION_CACHE_ENTRIES).isAtLeast(8)
        assertThat(EpgProductionPolicyContract.MAX_NORMALIZATION_CACHE_ENTRIES).isAtMost(4_096)
    }

    @Test
    fun decisionProvenanceIsExplicitBoundedAndSanitized() {
        val calibration = EpgProductionThresholdCalibration.calibrate(EpgProductionValidationCorpora.calibration)
        val holdout = EpgProductionValidationCorpora.holdout
        val gate = EpgProductionHoldoutGate.validate(calibration, EpgMatchAdversarialCorpus, holdout)

        assertThat(gate.holdoutProvenance).hasSize(holdout.cases.size)
        gate.holdoutProvenance.values.forEach { provenance ->
            assertThat(provenance.policyVersion).isEqualTo(EpgProductionPolicyContract.VALIDATION_POLICY_VERSION)
            assertThat(provenance.corpusSha256).isEqualTo(holdout.sha256)
            assertThat(provenance.thresholdSelectionDigestSha256).isEqualTo(calibration.selectionDigestSha256)
            assertThat(provenance.reason.name).isNotEmpty()
            assertThat(provenance.sourceBucket).isIn(holdout.sourceBuckets)
            assertThat(provenance.familyId).isIn(holdout.familyIds)
            val serialized = provenance.toString()
            assertThat(serialized).doesNotContain("http://")
            assertThat(serialized).doesNotContain("https://")
            assertThat(serialized).doesNotContain("Authorization")
            assertThat(serialized).doesNotContain("Cookie")
            assertThat(serialized).doesNotContain("token=")
        }
    }

    @Test
    fun zeroFailureConfidenceBoundIsReportedAsDiagnosticNotProof() {
        val upper = EpgProductionStatistics.zeroFailureUpperBound95(300)

        assertThat(upper).isGreaterThan(0.0)
        assertThat(upper).isLessThan(0.01)
    }

    @Test
    fun fuzzyEvidenceIsNonDestructiveByPolicyContract() {
        assertThat(EpgProductionPolicyContract.MANUAL_OVERRIDE_PRECEDENCE).isTrue()
        assertThat(EpgProductionPolicyContract.FUZZY_CAN_OVERRIDE_MANUAL_BINDING).isFalse()
        assertThat(EpgProductionPolicyContract.FUZZY_CAN_MUTATE_CANONICAL_IDENTITY).isFalse()
        assertThat(EpgProductionPolicyContract.FUZZY_CAN_CROSS_PROVIDER_BOUNDARY).isFalse()
        assertThat(EpgProductionPolicyContract.CANDIDATE_OVERFLOW_DECISION)
            .isEqualTo(EpgBenchmarkDecision.UNRESOLVED)
    }
}
