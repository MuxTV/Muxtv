package app.muxtv.benchmark.epgmatch

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
        assertThat(calibration.familyIds.intersect(holdout.familyIds)).isEmpty()
        assertThat(calibration.sourceBuckets.intersect(holdout.sourceBuckets)).isEmpty()
        assertThat(calibration.cases.minOf { it.candidates.size }).isAtLeast(8)
        assertThat(holdout.cases.minOf { it.candidates.size }).isAtLeast(8)
        assertThat(calibration.cases.maxOf { it.candidates.size }).isAtMost(EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES)
        assertThat(holdout.cases.maxOf { it.candidates.size }).isAtMost(EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES)
    }

    @Test
    fun holdoutCoversMandatoryProductionRiskBuckets() {
        val tags = EpgProductionValidationCorpora.holdout.cases.flatMapTo(linkedSetOf()) { it.tags }

        assertThat(tags).containsAtLeastElementsIn(
            listOf(
                "near-duplicate",
                "numeric-sibling",
                "timeshift",
                "regional",
                "unicode-digits",
                "cyrillic",
                "greek",
                "cjk",
                "nfkc",
                "quality",
                "ambiguous",
                "isolation",
                "hard-negative",
            ),
        )
    }

    @Test
    fun calibrationCannotConsultHoldoutAndSelectionIsEvidenceBacked() {
        val calibration = EpgProductionThresholdCalibration.calibrate(EpgProductionValidationCorpora.calibration)

        assertThat(calibration.selectionSource).isEqualTo(EpgProductionThresholdSelectionSource.CALIBRATION_BREAKPOINT_SWEEP)
        assertThat(calibration.calibrationSha256).isEqualTo(EpgProductionValidationCorpora.calibration.sha256)
        assertThat(calibration.selectedEvaluation.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(calibration.selectedEvaluation.metrics.autoRecall)
            .isAtLeast(EpgProductionPolicyContract.MIN_CALIBRATION_AUTO_RECALL)
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
        assertThat(gate.holdoutExactMissRecovery).isAtLeast(EpgProductionPolicyContract.MIN_HOLDOUT_EXACT_MISS_RECOVERY)
        assertThat(gate.automaticDecisionExposure).isAtLeast(EpgProductionPolicyContract.MIN_AUTOMATIC_DECISION_EXPOSURE)
        assertThat(gate.passed).isTrue()
    }

    @Test
    fun zeroFailureConfidenceBoundIsReportedAsDiagnosticNotProof() {
        val upper = EpgProductionStatistics.zeroFailureUpperBound95(300)

        assertThat(upper).isGreaterThan(0.0)
        assertThat(upper).isLessThan(0.01)
    }

    @Test
    fun fuzzyEvidenceIsNonDestructiveByPolicyContract() {
        assertThat(EpgProductionPolicyContract.FUZZY_CAN_MUTATE_CANONICAL_IDENTITY).isFalse()
        assertThat(EpgProductionPolicyContract.FUZZY_CAN_CROSS_PROVIDER_BOUNDARY).isFalse()
        assertThat(EpgProductionPolicyContract.CANDIDATE_OVERFLOW_DECISION)
            .isEqualTo(EpgBenchmarkDecision.UNRESOLVED)
    }
}
