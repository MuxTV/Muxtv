package app.muxtv.common.tracing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvTraceContractTest {
    @Test
    fun `trace taxonomy is closed stable and secret free`() {
        assertThat(MuxTvTraceSection.entries.map(MuxTvTraceSection::traceName)).containsExactly(
            "MuxTv.SourceRefresh",
            "MuxTv.M3uParse",
            "MuxTv.CatalogStage",
            "MuxTv.CatalogActivate",
            "MuxTv.EpgImport",
            "MuxTv.Search",
            "MuxTv.PlayerPrepare",
            "MuxTv.FirstFrame",
        ).inOrder()

        MuxTvTraceSection.entries.forEach { section ->
            assertThat(section.traceName).matches("MuxTv\\.[A-Za-z]+")
            assertThat(section.traceName).doesNotContain("://")
            assertThat(section.traceName).doesNotContain("?")
            assertThat(section.traceName).doesNotContain("=")
        }
    }

    @Test
    fun `disabled trace executes product block exactly once`() {
        var executions = 0

        val result = MuxTvTrace.disabled.section(MuxTvTraceSection.SEARCH) {
            executions += 1
            42
        }

        assertThat(result).isEqualTo(42)
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `trace category is fixed rather than caller supplied`() {
        assertThat(MuxTvTrace.category).isEqualTo("MuxTv")
    }
}
