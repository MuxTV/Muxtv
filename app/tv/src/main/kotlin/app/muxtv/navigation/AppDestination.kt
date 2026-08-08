package app.muxtv.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppDestination : NavKey {
    @Serializable
    data object Home : AppDestination

    @Serializable
    data object Channels : AppDestination

    @Serializable
    data object Guide : AppDestination

    @Serializable
    data object Search : AppDestination

    @Serializable
    data object Sources : AppDestination

    @Serializable
    data object Doctor : AppDestination

    @Serializable
    data object AddSource : AppDestination

    @Serializable
    data class Player(
        val channelId: String,
    ) : AppDestination {
        init {
            require(channelId.isNotBlank())
        }
    }

    companion object {
        val initial: AppDestination = Home
        val topLevel: List<AppDestination> = listOf(Home, Channels, Guide, Search, Sources, Doctor)
    }
}
