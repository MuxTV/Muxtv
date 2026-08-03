package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchQueryEncoderTest {
    @Test
    fun encodesUnicodeLetterNumberRunsAsIndependentPrefixes() {
        val tokens = SearchQueryEncoder.encode("Россия 1 HD")

        assertThat(tokens.map(SearchQueryToken::value)).containsExactly("Россия", "1", "HD").inOrder()
        assertThat(tokens.map(SearchQueryToken::ftsExpression))
            .containsExactly("Россия*", "1*", "HD*")
            .inOrder()
    }

    @Test
    fun punctuationAndRawFtsSyntaxBecomeSeparators() {
        val tokens = SearchQueryEncoder.encode("  foo:OR \"bar\" -baz_near%  ")

        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("foo", "OR", "bar", "baz", "near")
            .inOrder()
        tokens.forEach { token ->
            assertThat(token.ftsExpression).doesNotContain(":")
            assertThat(token.ftsExpression).doesNotContain("\"")
            assertThat(token.ftsExpression).doesNotContain("-")
            assertThat(token.ftsExpression).doesNotContain("%")
            assertThat(token.ftsExpression).endsWith("*")
        }
    }

    @Test
    fun keepsSupplementaryUnicodeLettersInOneToken() {
        val deseretLetter = String(Character.toChars(0x10400))
        val tokens = SearchQueryEncoder.encode("$deseretLetter$deseretLetter 7")

        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("$deseretLetter$deseretLetter", "7")
            .inOrder()
    }

    @Test
    fun capsTokensDeterministicallyAtPublicMaximum() {
        val tokens = SearchQueryEncoder.encode("one two three four five six seven eight")

        assertThat(tokens).hasSize(6)
        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("one", "two", "three", "four", "five", "six")
            .inOrder()
    }

    @Test
    fun blankOrPunctuationOnlyQueryHasNoTokens() {
        assertThat(SearchQueryEncoder.encode("   ---___%%%   ")).isEmpty()
    }

    @Test
    fun tokenDiagnosticsDoNotExposeSearchText() {
        val token = SearchQueryEncoder.encode("Секрет").single()

        assertThat(token.toString()).doesNotContain("Секрет")
        assertThat(token.toString()).contains("length=")
    }
}
