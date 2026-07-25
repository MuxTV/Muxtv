package app.muxtv.navigation

sealed interface AppDestination {
    data object Home : AppDestination
    data object Channels : AppDestination
    data object Guide : AppDestination
    data object Search : AppDestination
    data object Sources : AppDestination

    data class Player(
        val channelId: String,
    ) : AppDestination {
        init {
            require(channelId.isNotBlank())
        }
    }

    companion object {
        val initial: AppDestination = Home
        val topLevel: List<AppDestination> = listOf(Home, Channels, Guide, Search, Sources)
    }
}
