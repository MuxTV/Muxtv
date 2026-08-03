package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgMatchNormalizerTest {
    @Test
    fun providerIdUsesNfcAndTrimButPreservesCaseAndPunctuation() {
        assertThat(normalizeEpgProviderId("  Cafe\u0301-HD  ")).isEqualTo("Café-HD")
        assertThat(normalizeEpgProviderId("BBC-HD")).isNotEqualTo(normalizeEpgProviderId("bbc-hd"))
    }

    @Test
    fun providerIdReturnsNullForBlankInput() {
        assertThat(normalizeEpgProviderId(" \t\n ")).isNull()
        assertThat(normalizeEpgProviderId(null)).isNull()
    }

    @Test
    fun displayNameUsesNfcUnicodeWhitespaceCollapseAndRootCaseFold() {
        assertThat(normalizeEpgDisplayName("  Cafe\u0301\u00A0   NEWS  "))
            .isEqualTo("café news")
    }

    @Test
    fun displayNamePreservesPunctuationAndDoesNotTransliterate() {
        assertThat(normalizeEpgDisplayName("BBC-News")).isEqualTo("bbc-news")
        assertThat(normalizeEpgDisplayName("Россия 1")).isEqualTo("россия 1")
        assertThat(normalizeEpgDisplayName("Россия 1"))
            .isNotEqualTo(normalizeEpgDisplayName("Rossiya 1"))
    }

    @Test
    fun displayNameReturnsNullForUnicodeWhitespaceOnly() {
        assertThat(normalizeEpgDisplayName("\u00A0\u2003\t")).isNull()
        assertThat(normalizeEpgDisplayName(null)).isNull()
    }
}
