package app.muxtv.designsystem.component

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvNavigationRailMetricsTest {
    @Test
    fun `compact rail fits five destinations inside 360dp`() {
        val metrics = navigationRailMetrics(360.dp)

        assertThat(metrics.requiredHeight(itemCount = 5)).isAtMost(360.dp)
        assertThat(metrics.itemHeight).isEqualTo(48.dp)
    }

    @Test
    fun `normal rail preserves lounge geometry above compact threshold`() {
        val metrics = navigationRailMetrics(720.dp)

        assertThat(metrics.itemHeight).isEqualTo(56.dp)
        assertThat(metrics.brandHeight).isEqualTo(48.dp)
        assertThat(metrics.verticalPadding).isEqualTo(20.dp)
        assertThat(metrics.itemGap).isEqualTo(8.dp)
    }
}
