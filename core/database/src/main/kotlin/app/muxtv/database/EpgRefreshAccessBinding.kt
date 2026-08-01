package app.muxtv.database

internal fun epgRefreshAccessBindingMatches(
    expectedAccessRef: String?,
    currentAccessRef: String?,
): Boolean = expectedAccessRef == currentAccessRef
