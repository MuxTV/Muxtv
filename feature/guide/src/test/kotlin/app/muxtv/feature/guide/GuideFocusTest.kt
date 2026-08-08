package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideFocusTest {
    @Test
    fun `exact channel and programme identity wins over previous indices`() {
        val targetKey = key(7)
        val anchor = GuideFocusAnchor(
            channelId = "channel-b",
            programmeKey = targetKey,
            previousChannelIndex = 0,
            previousProgrammeIndex = 0,
        )

        val target = anchor.resolveAgainst(
            channels = listOf(
                focusChannel("channel-a", listOf(key(1))),
                focusChannel("channel-b", listOf(key(6), targetKey, key(8))),
            ),
        )

        assertThat(target?.channelId).isEqualTo("channel-b")
        assertThat(target?.channelIndex).isEqualTo(1)
        assertThat(target?.programmeKey).isEqualTo(targetKey)
        assertThat(target?.programmeIndex).isEqualTo(1)
    }

    @Test
    fun `exact identity detector distinguishes surviving and removed programme`() {
        val anchor = GuideFocusAnchor(
            channelId = "channel-b",
            programmeKey = key(7),
            previousChannelIndex = 1,
            previousProgrammeIndex = 0,
        )
        val surviving = listOf(
            focusChannel("channel-a", listOf(key(1))),
            focusChannel("channel-b", listOf(key(7), key(8))),
        )
        val removed = listOf(
            focusChannel("channel-a", listOf(key(1))),
            focusChannel("channel-b", listOf(key(8))),
        )

        assertThat(anchor.hasExactIdentityIn(surviving)).isTrue()
        assertThat(anchor.hasExactIdentityIn(removed)).isFalse()
    }

    @Test
    fun `channel only focus is exact only when channel has no programme identity`() {
        val anchor = GuideFocusAnchor(
            channelId = "channel-b",
            programmeKey = null,
            previousChannelIndex = 1,
            previousProgrammeIndex = 0,
        )

        assertThat(
            anchor.hasExactIdentityIn(
                listOf(focusChannel("channel-b", emptyList())),
            ),
        ).isTrue()
        assertThat(
            anchor.hasExactIdentityIn(
                listOf(focusChannel("channel-b", listOf(key(1)))),
            ),
        ).isFalse()
    }

    @Test
    fun `missing programme falls back within same surviving channel`() {
        val anchor = GuideFocusAnchor(
            channelId = "channel-b",
            programmeKey = key(99),
            previousChannelIndex = 1,
            previousProgrammeIndex = 2,
        )

        val target = anchor.resolveAgainst(
            channels = listOf(
                focusChannel("channel-a", listOf(key(1))),
                focusChannel("channel-b", listOf(key(10), key(11))),
            ),
        )

        assertThat(target?.channelId).isEqualTo("channel-b")
        assertThat(target?.programmeIndex).isEqualTo(1)
        assertThat(target?.programmeKey).isEqualTo(key(11))
    }

    @Test
    fun `missing channel uses deterministic nearest previous channel fallback`() {
        val anchor = GuideFocusAnchor(
            channelId = "removed-channel",
            programmeKey = key(99),
            previousChannelIndex = 2,
            previousProgrammeIndex = 0,
        )

        val target = anchor.resolveAgainst(
            channels = listOf(
                focusChannel("channel-a", listOf(key(1))),
                focusChannel("channel-b", listOf(key(2))),
                focusChannel("channel-c", listOf(key(3))),
            ),
        )

        assertThat(target?.channelId).isEqualTo("channel-b")
        assertThat(target?.channelIndex).isEqualTo(1)
        assertThat(target?.programmeKey).isEqualTo(key(2))
    }

    @Test
    fun `channel without programme remains a valid channel focus target`() {
        val anchor = GuideFocusAnchor(
            channelId = "channel-b",
            programmeKey = key(99),
            previousChannelIndex = 1,
            previousProgrammeIndex = 4,
        )

        val target = anchor.resolveAgainst(
            channels = listOf(
                focusChannel("channel-a", listOf(key(1))),
                focusChannel("channel-b", emptyList()),
            ),
        )

        assertThat(target?.channelId).isEqualTo("channel-b")
        assertThat(target?.programmeKey).isNull()
        assertThat(target?.programmeIndex).isNull()
    }

    @Test
    fun `empty viewport has no focus target`() {
        val anchor = GuideFocusAnchor(
            channelId = "channel-a",
            programmeKey = null,
            previousChannelIndex = 0,
            previousProgrammeIndex = 0,
        )

        assertThat(anchor.resolveAgainst(emptyList())).isNull()
    }

    @Test
    fun `focus diagnostics never expose channel or epg identity`() {
        val anchor = GuideFocusAnchor(
            channelId = "secret-channel-id",
            programmeKey = GuideProgrammeKey(
                epgSourceId = "secret-epg-source",
                epgRevisionNumber = 4,
                sequenceNumber = 9,
            ),
            previousChannelIndex = 1,
            previousProgrammeIndex = 2,
        )

        val text = anchor.toString()

        assertThat(text).doesNotContain("secret-channel-id")
        assertThat(text).doesNotContain("secret-epg-source")
    }

    private fun key(sequence: Long): GuideProgrammeKey = GuideProgrammeKey(
        epgSourceId = "epg",
        epgRevisionNumber = 1,
        sequenceNumber = sequence,
    )

    private fun focusChannel(
        channelId: String,
        programmeKeys: List<GuideProgrammeKey>,
    ): GuideFocusChannel = GuideFocusChannel(channelId, programmeKeys)
}
