package app.muxtv.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigationModelTest {
    @Test
    fun `single profile startup opens home without profile picker destination`() {
        assertThat(AppDestination.initial).isEqualTo(AppDestination.Home)
        assertThat(AppDestination.entries.map { it.name }).doesNotContain("Profiles")
        assertThat(AppDestination.entries.map { it.name })
            .containsExactly("Home", "Channels", "Guide", "Search")
            .inOrder()
    }
}
