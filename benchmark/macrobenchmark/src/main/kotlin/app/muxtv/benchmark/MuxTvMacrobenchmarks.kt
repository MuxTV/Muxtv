package app.muxtv.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MuxTvMacrobenchmarks {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = measureStartup(StartupMode.COLD)

    @Test
    fun warmStartup() = measureStartup(StartupMode.WARM)

    @Test
    fun homeToChannels() = measureJourney { openChannels() }

    @Test
    fun homeToSearch() = measureJourney { openSearch() }

    @Test
    fun homeToGuide() = measureJourney { openGuide() }

    @Test
    fun sourcesToDoctor() = measureJourney {
        openSources()
        openDoctor()
    }

    @Test
    fun channelsFocusRestoration() = measureJourney { restoreChannelsFocus() }

    private fun measureStartup(startupMode: StartupMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
            startupMode = startupMode,
            iterations = 10,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() },
        )
    }

    private fun measureJourney(
        journey: MuxTvCriticalUserJourneys.() -> Unit,
    ) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            },
            measureBlock = {
                MuxTvCriticalUserJourneys(this).journey()
            },
        )
    }
}
