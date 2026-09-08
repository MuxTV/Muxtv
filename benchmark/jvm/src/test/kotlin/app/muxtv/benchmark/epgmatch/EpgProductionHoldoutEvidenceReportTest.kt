package app.muxtv.benchmark.epgmatch

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Locale
import org.junit.Test

class EpgProductionHoldoutEvidenceReportTest {
    @Test
    fun writeIndependentHoldoutEvidence() {
        val c06Reference = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus).selectedEvaluation
        val calibration = EpgProductionThresholdCalibration.calibrate(EpgProductionValidationCorpora.calibration)
        val gate = EpgProductionHoldoutGate.validate(
            calibration = calibration,
            frozenC06 = EpgMatchAdversarialCorpus,
            holdout = EpgProductionValidationCorpora.holdout,
        )
        val headSha = System.getenv("MUXTV_EVIDENCE_SHA")
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: "0000000000000000000000000000000000000000"

        assertThat(c06Reference.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(gate.holdout.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(gate.frozenC06.metrics.falseAutomaticMatches).isEqualTo(3)
        assertThat(gate.passed).isFalse()
        assertThat(gate.disposition).isEqualTo(EpgProductionHoldoutDisposition.REJECT_AUTOMATIC_FUZZY)

        val report = buildString {
            appendLine("# C359 EPG production holdout evidence")
            appendLine()
            appendLine("- exact_head: `$headSha`")
            appendLine("- validation_policy_version: `${EpgProductionPolicyContract.VALIDATION_POLICY_VERSION}`")
            appendLine("- threshold_selection: `${calibration.selectionSource}`")
            appendLine("- selection_input_partition: `${calibration.selectionInputPartition}`")
            appendLine("- calibration_corpus_sha256: `${calibration.calibrationSha256}`")
            appendLine("- calibration_seed_partition_sha256: `${EpgProductionValidationCorpora.calibration.seedPartitionSha256}`")
            appendLine("- holdout_corpus_sha256: `${EpgProductionValidationCorpora.holdout.sha256}`")
            appendLine("- holdout_seed_partition_sha256: `${EpgProductionValidationCorpora.holdout.seedPartitionSha256}`")
            appendLine("- threshold_selection_digest_sha256: `${calibration.selectionDigestSha256}`")
            appendLine("- selected_auto_threshold: `${calibration.selectedThresholds.autoThreshold.f6()}`")
            appendLine("- selected_review_threshold: `${calibration.selectedThresholds.reviewThreshold.f6()}`")
            appendLine("- selected_auto_margin: `${calibration.selectedThresholds.autoMargin.f6()}`")
            appendLine("- evaluated_configurations: `${calibration.evaluatedConfigurations}`")
            appendLine("- frozen_c06_reference_false_auto: `${c06Reference.metrics.falseAutomaticMatches}`")
            appendLine("- production_policy_on_frozen_c06_false_auto: `${gate.frozenC06.metrics.falseAutomaticMatches}`")
            appendLine("- holdout_false_auto: `${gate.holdout.metrics.falseAutomaticMatches}`")
            appendLine("- automatic_fuzzy_admission: `REJECTED`")
            appendLine("- rejection_boundary: `FROZEN_C06_REGRESSION`")
            appendLine("- production_adoption: `REVIEW_ONLY_DEFERRED`")
            appendLine("- holdout_exact_miss_recovery: `${gate.holdoutExactMissRecovery.f6()}`")
            appendLine("- automatic_decision_exposure: `${gate.automaticDecisionExposure}`")
            appendLine("- fuzzy_automatic_decision_exposure: `${gate.fuzzyAutomaticDecisionExposure}`")
            appendLine("- zero_failure_upper_bound_95_diagnostic: `${gate.zeroFailureUpperBound95Diagnostic.f6()}`")
            appendLine("- exact_semantic_regressions: `${gate.exactSemanticRegressions}`")
            appendLine("- manual_override_precedence_preserved: `${gate.manualOverridePrecedencePreserved}`")
            appendLine("- provider_isolation_preserved: `${!EpgProductionPolicyContract.FUZZY_CAN_CROSS_PROVIDER_BOUNDARY}`")
            appendLine("- disposition: `${gate.disposition}`")
            appendLine("- holdout_compatibility_key_sha256: `${gate.holdoutCompatibilityKeySha256}`")
            appendLine()
            appendLine("## Holdout false-auto by risk bucket")
            gate.holdoutFalseAutoByRiskBucket.toSortedMap().forEach { (bucket, count) ->
                appendLine("- $bucket: `$count`")
            }
        }

        assertThat(report).contains("- frozen_c06_reference_false_auto: `0`")
        assertThat(report).contains("- production_policy_on_frozen_c06_false_auto: `3`")
        assertThat(report).contains("- holdout_false_auto: `0`")
        assertThat(report).contains("- automatic_fuzzy_admission: `REJECTED`")
        assertThat(report).contains("- rejection_boundary: `FROZEN_C06_REGRESSION`")
        assertThat(report).contains("- production_adoption: `REVIEW_ONLY_DEFERRED`")
        assertThat(report).contains("- disposition: `REJECT_AUTOMATIC_FUZZY`")
        assertThat(report).doesNotContain("http://")
        assertThat(report).doesNotContain("https://")
        assertThat(report).doesNotContain("Authorization")
        assertThat(report).doesNotContain("Cookie")

        val output = File("build/reports/c359/holdout.md")
        output.parentFile.mkdirs()
        output.writeText(report)
        println(
            "C359_EPG_HOLDOUT disposition=${gate.disposition} " +
                "c06_reference_false_auto=${c06Reference.metrics.falseAutomaticMatches} " +
                "production_c06_false_auto=${gate.frozenC06.metrics.falseAutomaticMatches} " +
                "holdout_false_auto=${gate.holdout.metrics.falseAutomaticMatches} " +
                "auto=${calibration.selectedThresholds.autoThreshold.f6()} " +
                "review=${calibration.selectedThresholds.reviewThreshold.f6()} " +
                "margin=${calibration.selectedThresholds.autoMargin.f6()}",
        )
    }

    private fun Double.f6(): String = String.format(Locale.ROOT, "%.6f", this)
}
