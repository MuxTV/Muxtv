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
            )
            .inOrder()
        assertThat(AppDestination.topLevel)
            .doesNotContain(AppDestination.Player(channelId = "channel-test"))
    }
}
