package app.muxtv.navigation

import androidx.compose.ui.unit.dp
import app.muxtv.designsystem.TvTokens
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigationModelTest {
    @Test
    fun `single profile startup opens home with bounded top level destinations`() {
        assertThat(AppDestination.initial).isEqualTo(AppDestination.Home)
        assertThat(AppDestination.topLevel)
            .containsExactly(
                AppDestination.Home,
                AppDestination.Channels,
                AppDestination.Guide,
                AppDestination.Search,
                AppDestination.Settings,
            )
            .inOrder()
        assertThat(AppDestination.topLevel).doesNotContain(AppDestination.AddSource)
        assertThat(AppDestination.topLevel).doesNotContain(AppDestination.Sources)
        assertThat(AppDestination.topLevel).doesNotContain(AppDestination.Doctor)
        assertThat(AppDestination.topLevel)
            .doesNotContain(AppDestination.Player(channelId = "channel-test"))
    }

    @Test
    fun `add source route carries no locator or preparation token`() {
        assertThat(AppDestination.AddSource.toString()).isEqualTo("AddSource")
    }

    @Test
    fun `u1 content reservation stays collapsed while rail is visible`() {
        assertThat(railContentReservation(railVisible = true))
            .isEqualTo(TvTokens.Size.railCollapsed)
        assertThat(railContentReservation(railVisible = false)).isEqualTo(0.dp)
    }
}
