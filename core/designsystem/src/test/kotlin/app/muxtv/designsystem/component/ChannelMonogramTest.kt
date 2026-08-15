package app.muxtv.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelMonogramTest {
    @Test
    fun `single word uses first two letters uppercased`() {
        assertThat(channelMonogram("Первый")).isEqualTo("ПЕ")
        assertThat(channelMonogram("HD")).isEqualTo("HD")
    }

    @Test
    fun `multiple words use first letter of first two words`() {
        assertThat(channelMonogram("Первый Канал")).isEqualTo("ПК")
        assertThat(channelMonogram("NTV Mir")).isEqualTo("NM")
    }

    @Test
    fun `long russian name stays bounded to two characters`() {
        assertThat(channelMonogram("Очень Длинное Название Канала Телевидения")).isEqualTo("ОД")
    }

    @Test
    fun `blank name falls back to placeholder`() {
        assertThat(channelMonogram("   ")).isEqualTo("?")
    }
}
