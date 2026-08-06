package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupRestorePreviewTest {
    @Test
    fun `preview summarizes portable rows without mutating or inventing conflicts`() {
        val document = decodedDocument()

        val preview = BackupRestorePreviewer.preview(
            document = document,
            existingState = ExistingBackupState(
                profileIds = emptySet(),
                sourceIds = emptySet(),
            ),
        )

        assertThat(preview.profileCount).isEqualTo(1)
        assertThat(preview.sourceCount).isEqualTo(1)
        assertThat(preview.overlayCount).isEqualTo(1)
        assertThat(preview.recentCount).isEqualTo(1)
        assertThat(preview.sourcesRequiringReauth).isEqualTo(1)
        assertThat(preview.conflicts).isEmpty()
        assertThat(preview.requiresExplicitConflictDecision).isFalse()
    }

    @Test
    fun `existing profile and source identities become explicit conflicts`() {
        val preview = BackupRestorePreviewer.preview(
            document = decodedDocument(),
            existingState = ExistingBackupState(
                profileIds = setOf("profile-a"),
                sourceIds = setOf("source-a"),
            ),
        )

        assertThat(preview.conflicts.map(BackupRestoreConflict::kind)).containsExactly(
            BackupConflictKind.PROFILE_ID,
            BackupConflictKind.SOURCE_ID,
        )
        assertThat(preview.requiresExplicitConflictDecision).isTrue()
    }

    @Test
    fun `all portable sources are counted as requiring access re-entry`() {
        val document = decodedDocument()

        val preview = BackupRestorePreviewer.preview(
            document = document,
            existingState = ExistingBackupState(),
        )

        assertThat(document.snapshot.payload.sources)
            .containsExactly(
                PortableBackupSource(
                    id = "source-a",
                    name = "Source A",
                    recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
                ),
            )
        assertThat(preview.sourcesRequiringReauth).isEqualTo(document.snapshot.payload.sources.size)
    }

    @Test
    fun `preview diagnostics redact ids names and channel identity`() {
        val preview = BackupRestorePreviewer.preview(
            document = decodedDocument(),
            existingState = ExistingBackupState(
                profileIds = setOf("profile-a"),
                sourceIds = setOf("source-a"),
            ),
        )

        val diagnostics = buildString {
            append(preview)
            append('\n')
            preview.conflicts.forEach { append(it).append('\n') }
        }

        assertThat(diagnostics).doesNotContain("profile-a")
        assertThat(diagnostics).doesNotContain("source-a")
        assertThat(diagnostics).doesNotContain("Profile A")
        assertThat(diagnostics).doesNotContain("Source A")
        assertThat(diagnostics).doesNotContain("channel-a")
        assertThat(diagnostics).contains("conflictCount=2")
    }

    private fun decodedDocument(): PortableBackupDocument {
        val snapshot = PortableBackupSnapshot(
            createdAtEpochMillis = 1_786_000_000_000L,
            dataSchemaVersion = 10,
            payload = PortableBackupPayload(
                profiles = listOf(
                    PortableBackupProfile(
                        id = "profile-a",
                        name = "Profile A",
                        isPrimary = true,
                    ),
                ),
                sources = listOf(
                    PortableBackupSource(
                        id = "source-a",
                        name = "Source A",
                        recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
                    ),
                ),
                channelOverlays = listOf(
                    PortableChannelOverlay(
                        profileId = "profile-a",
                        canonicalChannelId = "channel-a",
                        isFavorite = true,
                    ),
                ),
                recentChannels = listOf(
                    PortableRecentChannel(
                        profileId = "profile-a",
                        canonicalChannelId = "channel-a",
                        lastSuccessfulPlaybackAtEpochMillis = 1_785_999_000_000L,
                    ),
                ),
            ),
        )
        return (PortableBackupCodec.decode(PortableBackupCodec.encode(snapshot))
            as PortableBackupDecodeResult.Success).document
    }
}
