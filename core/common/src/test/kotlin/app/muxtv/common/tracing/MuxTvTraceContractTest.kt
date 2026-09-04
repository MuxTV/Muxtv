package app.muxtv.common.tracing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
    fun `disabled coroutine trace executes product block exactly once`() = runBlocking {
        var executions = 0

        val result = MuxTvTrace.disabled.coroutineSection(MuxTvTraceSection.M3U_PARSE) {
            executions += 1
            43
        }

        assertThat(result).isEqualTo(43)
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `trace failure before product block falls back without duplicate execution`() {
        var executions = 0
        val trace = MuxTvTrace.forTesting(ThrowingBeforeBackend)

        val result = trace.section(MuxTvTraceSection.SEARCH) {
            executions += 1
            44
        }

        assertThat(result).isEqualTo(44)
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `trace failure after product block preserves product result without duplicate execution`() {
        var executions = 0
        val trace = MuxTvTrace.forTesting(ThrowingAfterBackend)

        val result = trace.section(MuxTvTraceSection.SEARCH) {
            executions += 1
            45
        }

        assertThat(result).isEqualTo(45)
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `coroutine trace failure after product block preserves product result`() = runBlocking {
        var executions = 0
        val trace = MuxTvTrace.forTesting(ThrowingAfterBackend)

        val result = trace.coroutineSection(MuxTvTraceSection.CATALOG_ACTIVATE) {
            executions += 1
            46
        }

        assertThat(result).isEqualTo(46)
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun `product failure remains authoritative when tracer also fails`() {
        val expected = IllegalArgumentException("product failure")
        val trace = MuxTvTrace.forTesting(ThrowingAfterBackend)

        val actual = try {
            trace.section(MuxTvTraceSection.SEARCH) { throw expected }
            null
        } catch (failure: Throwable) {
            failure
        }

        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun `trace category is fixed rather than caller supplied`() {
        assertThat(MuxTvTrace.category).isEqualTo("MuxTv")
    }
}

private object ThrowingBeforeBackend : MuxTvTraceBackend {
    override fun <T> trace(
        section: MuxTvTraceSection,
        block: () -> T,
    ): T = throw IllegalStateException("trace infrastructure failure")

    override suspend fun <T> traceCoroutine(
        section: MuxTvTraceSection,
        block: suspend () -> T,
    ): T = throw IllegalStateException("trace infrastructure failure")
}

private object ThrowingAfterBackend : MuxTvTraceBackend {
    override fun <T> trace(
        section: MuxTvTraceSection,
        block: () -> T,
    ): T {
        block()
        throw IllegalStateException("trace infrastructure failure")
    }

    override suspend fun <T> traceCoroutine(
        section: MuxTvTraceSection,
        block: suspend () -> T,
    ): T {
        block()
        throw IllegalStateException("trace infrastructure failure")
    }
}
