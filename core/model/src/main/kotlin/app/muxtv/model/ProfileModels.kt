package app.muxtv.model

import app.muxtv.common.ProfileId as CommonProfileId

typealias ProfileId = CommonProfileId

data class UserProfile private constructor(
    val id: ProfileId,
    val name: String,
    val isPrimary: Boolean,
) {
    val isDeletable: Boolean get() = !isPrimary

    init {
        require(name.isNotBlank()) { "Profile name must not be blank" }
    }

    companion object {
        const val PRIMARY_INITIAL_NAME: String = "Основной"

        fun primary(id: ProfileId): UserProfile = UserProfile(
            id = id,
            name = PRIMARY_INITIAL_NAME,
            isPrimary = true,
        )

        fun additional(id: ProfileId, name: String): UserProfile = UserProfile(
            id = id,
            name = name.trim(),
            isPrimary = false,
        )
    }

    fun renamed(name: String): UserProfile = copy(name = name.trim())
}
