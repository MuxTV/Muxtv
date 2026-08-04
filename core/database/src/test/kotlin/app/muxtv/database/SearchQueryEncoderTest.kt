package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchQueryEncoderTest {
    @Test
    fun encodesUnicodeLetterNumberRunsAsIndependentQuotedPrefixes() {
        val tokens = SearchQueryEncoder.encode("Россия 1 HD")

        assertThat(tokens.map(SearchQueryToken::value)).containsExactly("Россия", "1", "HD").inOrder()
        assertThat(tokens.map(SearchQueryToken::ftsExpression))
            .containsExactly("\"Россия*\"", "\"1*\"", "\"HD*\"")
            .inOrder()
        assertThat(SearchQueryEncoder.hasTokenOverflow("Россия 1 HD")).isFalse()
    }

    @Test
    fun punctuationAndRawFtsSyntaxBecomeSeparatorsAndOperatorsBecomeQuotedTerms() {
        val tokens = SearchQueryEncoder.encode("  foo:OR \"bar\" -baz_near%  ")

        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("foo", "OR", "bar", "baz", "near")
            .inOrder()
        assertThat(tokens.map(SearchQueryToken::ftsExpression)).containsExactly(
            "\"foo*\"",
            "\"OR*\"",
            "\"bar*\"",
            "\"baz*\"",
            "\"near*\"",
        ).inOrder()
    }

    @Test
    fun keepsSupplementaryUnicodeLettersInOneToken() {
        val deseretLetter = String(Character.toChars(0x10400))
        val tokens = SearchQueryEncoder.encode("$deseretLetter$deseretLetter 7")

        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("$deseretLetter$deseretLetter", "7")
            .inOrder()
        assertThat(tokens.first().ftsExpression).startsWith("\"")
        assertThat(tokens.first().ftsExpression).endsWith("*\"")
    }

    @Test
    fun capsTokensDeterministicallyAndReportsOverflow() {
        val text = "one two three four five six seven eight"
        val tokens = SearchQueryEncoder.encode(text)

        assertThat(tokens).hasSize(6)
        assertThat(tokens.map(SearchQueryToken::value))
            .containsExactly("one", "two", "three", "four", "five", "six")
            .inOrder()
        assertThat(SearchQueryEncoder.hasTokenOverflow(text)).isTrue()
    }

    @Test
    fun blankOrPunctuationOnlyQueryHasNoTokens() {
        assertThat(SearchQueryEncoder.encode("   ---___%%%   ")).isEmpty()
        assertThat(SearchQueryEncoder.hasTokenOverflow("   ---___%%%   ")).isFalse()
    }

    @Test
    fun tokenDiagnosticsDoNotExposeSearchText() {
        val token = SearchQueryEncoder.encode("Секрет").single()

        assertThat(token.toString()).doesNotContain("Секрет")
        assertThat(token.toString()).contains("length=")
    }
}
