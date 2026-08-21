package app.muxtv.designsystem.component

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvNavigationRailMetricsTest {
    @Test
    fun `reference rail fits five destinations inside 360dp`() {
        val metrics = navigationRailMetrics(
            availableHeight = 360.dp,
            itemCount = 5,
        )

        assertThat(metrics.requiredHeight(itemCount = 5)).isAtMost(360.dp)
        assertThat(metrics.itemHeight).isEqualTo(36.dp)
        assertThat(metrics.brandHeight).isEqualTo(28.dp)
        assertThat(metrics.verticalPadding).isEqualTo(20.dp)
        assertThat(metrics.brandToItemsGap).isEqualTo(28.dp)
        assertThat(metrics.itemGap).isEqualTo(8.dp)
    }

    @Test
    fun `normal rail preserves reference geometry when it fits`() {
        val metrics = navigationRailMetrics(
            availableHeight = 720.dp,
            itemCount = 5,
        )

        assertThat(metrics.requiredHeight(itemCount = 5)).isEqualTo(308.dp)
        assertThat(metrics.itemHeight).isEqualTo(36.dp)
        assertThat(metrics.brandHeight).isEqualTo(28.dp)
        assertThat(metrics.verticalPadding).isEqualTo(20.dp)
        assertThat(metrics.brandToItemsGap).isEqualTo(28.dp)
        assertThat(metrics.itemGap).isEqualTo(8.dp)
    }

    @Test
    fun `rail switches to compact exactly when reference geometry no longer fits`() {
        val exactFit = navigationRailMetrics(
            availableHeight = 308.dp,
            itemCount = 5,
        )
        val oneDpShort = navigationRailMetrics(
            availableHeight = 307.dp,
            itemCount = 5,
        )

        assertThat(exactFit.itemHeight).isEqualTo(36.dp)
        assertThat(oneDpShort.itemHeight).isEqualTo(32.dp)
        assertThat(oneDpShort.requiredHeight(itemCount = 5)).isAtMost(307.dp)
    }
}
