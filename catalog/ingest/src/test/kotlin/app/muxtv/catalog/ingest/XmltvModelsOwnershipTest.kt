package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Assert.assertThrows
import org.junit.Test

class XmltvModelsOwnershipTest {
    @Test
    fun `singleton programme metadata remains independently immutable`() {
        val mutableTitles = mutableListOf(XmltvText("Original", "en"))
        val programme = programme(titles = mutableTitles)

        mutableTitles[0] = XmltvText("Mutated", "en")

        assertThat(programme.titles.single().value).isEqualTo("Original")
        @Suppress("UNCHECKED_CAST")
        val exposed = programme.titles as MutableList<XmltvText>
        assertThrows(UnsupportedOperationException::class.java) {
            exposed += XmltvText("Injected", "en")
        }
    }

    @Test
    fun `empty programme metadata remains immutable`() {
        val programme = programme(titles = emptyList())

        @Suppress("UNCHECKED_CAST")
        val exposed = programme.categories as MutableList<XmltvText>
        assertThrows(UnsupportedOperationException::class.java) {
            exposed += XmltvText("Injected", null)
        }
        assertThat(programme.categories).isEmpty()
    }

    private fun programme(titles: List<XmltvText>): XmltvProgramme = XmltvProgramme(
        externalChannelId = "channel",
        start = XmltvTimestamp.Unresolved(
            localDateTime = LocalDateTime.of(2026, 8, 2, 12, 0),
            precision = XmltvTimestampPrecision.Minute,
            inferredComponents = false,
        ),
        stop = null,
        pdcStart = null,
        vpsStart = null,
        titles = titles,
        subTitles = emptyList(),
        descriptions = emptyList(),
        categories = emptyList(),
        keywords = emptyList(),
        countries = emptyList(),
        urls = emptyList(),
        icons = emptyList(),
        episodeNumbers = emptyList(),
        credits = emptyList(),
        previouslyShown = false,
        premiere = false,
        lastChance = false,
        isNew = false,
    )
}
