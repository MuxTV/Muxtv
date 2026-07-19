package app.muxtv.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProfileInvariantTest {
    @Test
    fun `primary profile uses the required initial identity without role semantics`() {
        val profile = UserProfile.primary(ProfileId("profile-main"))
        assertThat(profile.name).isEqualTo("Основной")
        assertThat(profile.isPrimary).isTrue()
        assertThat(profile.isDeletable).isFalse()
    }

    @Test
    fun `additional profile accepts arbitrary user defined name`() {
        val profile = UserProfile.additional(ProfileId("profile-2"), "Кабинет")
        assertThat(profile.name).isEqualTo("Кабинет")
        assertThat(profile.isPrimary).isFalse()
        assertThat(profile.isDeletable).isTrue()
    }
}
