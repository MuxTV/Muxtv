package app.muxtv.navigation

enum class AppDestination {
    Home,
    Channels,
    Guide,
    Search;

    companion object { val initial: AppDestination = Home }
}
