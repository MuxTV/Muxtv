package app.muxtv.navigation

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
                AppDestination.Sources,
            )
            .inOrder()
        assertThat(AppDestination.topLevel).doesNotContain(AppDestination.AddSource)
        assertThat(AppDestination.topLevel)
            .doesNotContain(AppDestination.Player(channelId = "channel-test"))
    }

    @Test
    fun `add source route carries no locator or preparation token`() {
        assertThat(AppDestination.AddSource.toString()).isEqualTo("AddSource")
    }
}
