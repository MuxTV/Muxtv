package app.muxtv.benchmark.epgmatch

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Locale
import org.junit.Test

class EpgMatchEvidenceReportTest {
    @Test
    fun writeCorrectnessEvidence() {
        val baseline = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.A_CURRENT_EXACT,
            EpgMatchAdversarialCorpus,
        )
        val sweep = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus)
        val hybrid = sweep.selectedEvaluation
        val reference = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.C_OWNTV_REFERENCE,
            EpgMatchAdversarialCorpus,
        )
        val headSha = System.getenv("MUXTV_EVIDENCE_SHA")
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: "0000000000000000000000000000000000000000"
        val scenario = EpgCompetitiveContract.scenario(headSha)

        assertThat(baseline.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(hybrid.metrics.falseAutomaticMatches).isEqualTo(0)
        assertThat(hybrid.metrics.autoPrecision).isEqualTo(1.0)

        val report = buildString {
            appendLine("# C06 EPG matching correctness evidence")
            appendLine()
            appendLine("- exact_head: `$headSha`")
            appendLine("- corpus_content_sha256: `${scenario.corpus.contentSha256}`")
            appendLine("- corpus_manifest_sha256: `${scenario.corpus.manifestSha256}`")
            appendLine("- corpus_cases: `${EpgMatchAdversarialCorpus.cases.size}`")
            appendLine("- threshold_selection: `${sweep.selectionSource}`")
            appendLine("- threshold_configurations: `${sweep.evaluatedConfigurations}`")
            appendLine("- selected_auto_threshold: `${sweep.selectedThresholds.autoThreshold.f4()}`")
            appendLine("- selected_review_threshold: `${sweep.selectedThresholds.reviewThreshold.f4()}`")
            appendLine("- selected_auto_margin: `${sweep.selectedThresholds.autoMargin.f4()}`")
            appendLine("- owntv_reference_auto_threshold: `${sweep.referenceAutoThreshold.f4()}`")
            appendLine("- owntv_reference_review_threshold: `${sweep.referenceReviewThreshold.f4()}`")
            appendLine("- sweep_digest_sha256: `${sweep.reportDigest}`")
            appendLine()
            appendLine("| Variant | Precision | False auto | Recall | Ambiguity/review | MRR | Top-1 | Top-3 | Unresolved |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            appendMetrics("A current exact", baseline.metrics)
            appendMetrics("B hybrid selected", hybrid.metrics)
            appendMetrics("C OwnTV reference", reference.metrics)
            appendLine()
            appendLine("C01 correctness digests:")
            appendLine("- A: `${EpgCompetitiveContract.correctness(baseline).digestSha256}`")
            appendLine("- B: `${EpgCompetitiveContract.correctness(hybrid).digestSha256}`")
            appendLine("- C: `${EpgCompetitiveContract.correctness(reference).digestSha256}`")
        }
        val output = File("build/reports/c06/correctness.md")
        output.parentFile.mkdirs()
        output.writeText(report)
        println("C06_EPG_RESULT " + report.lineSequence().filter { it.startsWith("| A ") || it.startsWith("| B ") || it.startsWith("| C ") }.joinToString(" ; "))
        println("C06_EPG_THRESHOLDS auto=${sweep.selectedThresholds.autoThreshold.f4()} review=${sweep.selectedThresholds.reviewThreshold.f4()} margin=${sweep.selectedThresholds.autoMargin.f4()} configurations=${sweep.evaluatedConfigurations}")
        println("C06_EPG_CORPUS content=${EpgMatchAdversarialCorpus.sha256} manifest=${EpgMatchAdversarialCorpus.manifestSha256}")
    }

    private fun StringBuilder.appendMetrics(label: String, metrics: EpgBenchmarkMetrics) {
        appendLine(
            "| $label | ${metrics.autoPrecision.f4()} | ${metrics.falseAutomaticMatches} | " +
                "${metrics.autoRecall.f4()} | ${metrics.ambiguityReviewRate.f4()} | " +
                "${metrics.meanReciprocalRank.f4()} | ${metrics.top1HitRate.f4()} | " +
                "${metrics.top3HitRate.f4()} | ${metrics.unresolvedRate.f4()} |",
        )
    }

    private fun Double.f4(): String = String.format(Locale.ROOT, "%.4f", this)
}
