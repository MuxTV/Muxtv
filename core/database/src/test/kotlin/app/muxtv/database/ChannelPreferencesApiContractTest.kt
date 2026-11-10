package app.muxtv.database

import app.muxtv.catalog.ChannelPreferencesRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelPreferencesApiContractTest {
    @Test
    fun channelControlMutationsAreExposedByCatalogPort() {
        val methodNames = ChannelPreferencesRepository::class.java.methods
            .map { method -> method.name }
            .toSet()

        assertThat(methodNames).containsAtLeast(
            "setFavorite",
            "setHidden",
            "setCustomName",
            "setChannelNumber",
            "resetCustomization",
        )
    }
}
