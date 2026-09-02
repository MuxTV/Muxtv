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
    data object Settings : AppDestination

    @Serializable
    data object Sources : AppDestination

    @Serializable
    data object Doctor : AppDestination

    @Serializable
    data object AddSource : AppDestination

    @Serializable
    data class Player(
        val channelId: String,
        val programmeId: String? = null,
        val programmeStartEpochMillis: Long? = null,
        val programmeEndEpochMillis: Long? = null,
    ) : AppDestination {
        init {
            require(channelId.isNotBlank())
            val programmeTuple = listOf(
                programmeId,
                programmeStartEpochMillis,
                programmeEndEpochMillis,
            )
            require(programmeTuple.all { it == null } || programmeTuple.all { it != null })
            if (programmeId != null &&
                programmeStartEpochMillis != null &&
                programmeEndEpochMillis != null
            ) {
                require(programmeId.isNotBlank())
                require(programmeId.length <= MAX_PROGRAMME_ID_LENGTH)
                require(programmeStartEpochMillis >= 0L)
                require(programmeEndEpochMillis > programmeStartEpochMillis)
            }
        }

        override fun toString(): String =
            "AppDestination.Player(channelId=<redacted>, " +
                "programmePresent=${programmeId != null})"
    }

    companion object {
        val initial: AppDestination = Home
        val topLevel: List<AppDestination> = listOf(Home, Channels, Guide, Search, Settings)
    }
}

private const val MAX_PROGRAMME_ID_LENGTH = 80
