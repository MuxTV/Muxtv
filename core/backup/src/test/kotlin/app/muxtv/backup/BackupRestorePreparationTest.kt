package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupRestorePreparationTest {
    @Test
    fun `valid backup always requires preview before mutation`() {
        val result = BackupRestorePreparer.prepare(
            bytes = PortableBackupCodec.encode(validSnapshot()),
            existingState = ExistingBackupState(),
        )

        assertThat(result)
            .isInstanceOf(BackupRestorePreparationResult.PreviewRequired::class.java)
    }

    @Test
    fun `conflicts remain explicit in required preview`() {
        val result = BackupRestorePreparer.prepare(
            bytes = PortableBackupCodec.encode(validSnapshot()),
            existingState = ExistingBackupState(
                profileIds = setOf(PROFILE_ID),
                sourceIds = setOf(SOURCE_ID),
            ),
        ) as BackupRestorePreparationResult.PreviewRequired

        assertThat(result.preview.requiresExplicitConflictDecision).isTrue()
        assertThat(result.preview.conflicts).hasSize(2)
    }

    @Test
    fun `portable sources remain reauth required in preview`() {
        val result = BackupRestorePreparer.prepare(
            bytes = PortableBackupCodec.encode(validSnapshot()),
            existingState = ExistingBackupState(),
        ) as BackupRestorePreparationResult.PreviewRequired

        assertThat(result.preview.sourceCount).isEqualTo(1)
        assertThat(result.preview.sourcesRequiringReauth).isEqualTo(result.preview.sourceCount)
    }

    @Test
    fun `malformed backup is rejected without preview`() {
        val result = BackupRestorePreparer.prepare(
            bytes = byteArrayOf('{'.code.toByte()),
            existingState = ExistingBackupState(),
        )

        assertThat(result).isEqualTo(
            BackupRestorePreparationResult.Rejected(PortableBackupRejectReason.MALFORMED),
        )
    }

    @Test
    fun `oversized backup preserves package A rejection reason`() {
        val result = BackupRestorePreparer.prepare(
            bytes = ByteArray(PortableBackupLimits.MAX_DOCUMENT_BYTES + 1),
            existingState = ExistingBackupState(),
        )

        assertThat(result).isEqualTo(
            BackupRestorePreparationResult.Rejected(PortableBackupRejectReason.OVERSIZED),
        )
    }

    @Test
    fun `preparation diagnostics remain payload free`() {
        val result = BackupRestorePreparer.prepare(
            bytes = PortableBackupCodec.encode(validSnapshot()),
            existingState = ExistingBackupState(
                profileIds = setOf(PROFILE_ID),
                sourceIds = setOf(SOURCE_ID),
            ),
        )

        val diagnostic = result.toString()
        assertThat(diagnostic).doesNotContain(PROFILE_ID)
        assertThat(diagnostic).doesNotContain(PROFILE_NAME)
        assertThat(diagnostic).doesNotContain(SOURCE_ID)
        assertThat(diagnostic).doesNotContain(SOURCE_NAME)
        assertThat(diagnostic).doesNotContain(CHANNEL_ID)
        assertThat(diagnostic).doesNotContain(CUSTOM_CHANNEL_NAME)
    }

    private fun validSnapshot(): PortableBackupSnapshot = PortableBackupSnapshot(
        createdAtEpochMillis = 1_786_000_000_000L,
        dataSchemaVersion = 10,
        payload = PortableBackupPayload(
            profiles = listOf(
                PortableBackupProfile(
                    id = PROFILE_ID,
                    name = PROFILE_NAME,
                    isPrimary = true,
                ),
            ),
            sources = listOf(
                PortableBackupSource(
                    id = SOURCE_ID,
                    name = SOURCE_NAME,
                ),
            ),
            channelOverlays = listOf(
                PortableChannelOverlay(
                    profileId = PROFILE_ID,
                    canonicalChannelId = CHANNEL_ID,
                    isFavorite = true,
                    customName = CUSTOM_CHANNEL_NAME,
                    channelNumber = 7,
                ),
            ),
            recentChannels = listOf(
                PortableRecentChannel(
                    profileId = PROFILE_ID,
                    canonicalChannelId = CHANNEL_ID,
                    lastSuccessfulPlaybackAtEpochMillis = 1_786_000_000_001L,
                ),
            ),
        ),
    )

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val PROFILE_NAME = "Основной профиль"
        const val SOURCE_ID = "source-1"
        const val SOURCE_NAME = "Домашнее ТВ"
        const val CHANNEL_ID = "channel-1"
        const val CUSTOM_CHANNEL_NAME = "Любимый канал"
    }
}
