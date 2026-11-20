package app.muxtv.sources

import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.DefaultRemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceAccessManager
import app.muxtv.catalog.refresh.RemoteSourceActivationCleanup
import app.muxtv.catalog.refresh.RemoteSourceActivator
import app.muxtv.catalog.refresh.RemoteSourceMetadataCleanupResult
import app.muxtv.catalog.refresh.RemoteSourceRefreshRequest
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.onboarding.Lc04TvOriginalAeadCommandOwner
import app.muxtv.localcontrol.onboarding.ProtectedSourceStaging
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Product composition-root contract, not another fake SourceOnboarding test:
 * ORIGINAL TV QR/Ed25519 F6-bound encrypted records -> TV-owned
 * ProtectedSourceStaging -> actual AppSourceOnboarding ->
 * DurableRemoteSourceOnboarding -> DefaultRemoteSourceOnboarding.
 *
 * Only the remote refresher result and persistence ports are controllable
 * fixtures; Android Room/importer and actual Media3 remain independent device
 * gates. Real TCP F6/EOF is separately covered by the API26/API36 phone gate.
 */
class EncryptedM3uDurableSourceBoundaryTest {
    private class Credentials : CredentialStore {
        private val values = linkedMapOf<CredentialId, ByteArray>()
        val count get() = values.size

        override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
            values.remove(id)?.fill(0)
            values[id] = secret.copyBytes()
            return CredentialWriteResult.Stored
        }
        override suspend fun read(id: CredentialId): CredentialReadResult =
            values[id]?.let { CredentialReadResult.Found(SecretBytes.copyOf(it)) }
                ?: CredentialReadResult.NotFound

        override suspend fun remove(id: CredentialId): CredentialRemoveResult {
            val removed = values.remove(id) ?: return CredentialRemoveResult.NotFound
            removed.fill(0)
            return CredentialRemoveResult.Removed
        }
        override suspend fun reset(): CredentialResetResult {
            values.values.forEach(ByteArray::fillZero)
            values.clear()
            return CredentialResetResult.Reset
        }
        fun erase() { values.values.forEach { it.fill(0) }; values.clear() }
        private fun ByteArray.fillZero() = fill(0)
    }

    private class Pending : PendingSourcePreparationStore {
        val rows = linkedMapOf<String, PendingSourcePreparation>()
        override suspend fun upsert(preparation: PendingSourcePreparation) {
            rows[preparation.preparationId] = preparation
        }
        override suspend fun remove(preparationId: String): Boolean =
            rows.remove(preparationId) != null
        override suspend fun get(preparationId: String): PendingSourcePreparation? =
            rows[preparationId]
        override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? =
            rows.values.filter { it.expiresAtEpochMillis > nowEpochMillis }
                .maxByOrNull { it.createdAtEpochMillis }
        override suspend fun getExpired(
            nowEpochMillis: Long, limit: Int,
        ): List<PendingSourcePreparation> =
            rows.values.filter { it.expiresAtEpochMillis <= nowEpochMillis }.take(limit)
    }

    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000L)
        val originalTv = TvHpkeQrSession.create(clock.get())
        val scannedTv = TvQrPublicKeyParser.parse(
            checkNotNull(originalTv.presentation(clock.get())).qrValue,
        )
        val phoneSigner = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(scannedTv, phoneSigner, clock::get)
        val credentials = Credentials()
        val pending = Pending()
        val refreshes = mutableListOf<RemoteSourceRefreshRequest>()
        val onboarding = AppSourceOnboarding(
            DurableRemoteSourceOnboarding(
                delegate = DefaultRemoteSourceOnboarding(
                    accessManager = RemoteSourceAccessManager(credentials),
                    activator = RemoteSourceActivator { request ->
                        refreshes += request
                        RemoteSourceRefreshResult.Refreshed(
                            revisionNumber = 1, previousRevisionNumber = 0,
                            entryCount = 2, skippedEntries = 0, warningCount = 0,
                        )
                    },
                    activationCleanup = RemoteSourceActivationCleanup { _, _ ->
                        RemoteSourceMetadataCleanupResult.NotFound
                    },
                ),
                registry = pending,
                currentTimeMillis = { 10_000L },
            ),
        )
        private var tvRecords: Lc04SourceAeadPort? = null
        private var phoneRecords: Lc04SourceAeadPort? = null
        var owner: Lc04TvOriginalAeadCommandOwner? = null

        fun beginOriginalTvF6() {
            val gate = checkNotNull(originalTv.phoneEnrollmentGate(clock::get))
            val challenge = checkNotNull(gate.begin(phoneSigner.publicKeysetBytes()))
            assertThat(gate.approveOnTv(phoneSigner.fingerprint)).isTrue()
            val verified = gate.verify(phoneSigner.prove(scannedTv, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(originalTv.activateVerifiedPhone(verified.identity, clock.get())).isTrue()

            val offer = checkNotNull(phone.createSignedSeedOffer())
            val f2 = checkNotNull(originalTv.acceptSyntheticMxH1SeedOffer(offer, clock.get()))
            val f3 = checkNotNull(phone.acceptTvConfirmationAwaitingFinished(f2))
            val f4 = checkNotNull(originalTv.finishSyntheticMxH1ForProbeLab(f3, clock::get))
            assertThat(phone.acceptTvFinishedForProbeLab(f4, clock::get)).isTrue()
            val f5 = checkNotNull(phone.sealMxH1SyntheticProbeForLab())
            assertThat(originalTv.acceptMxH1SyntheticProbeForLab(f5, clock.get())).isTrue()
            val f6 = checkNotNull(originalTv.sealMxH1SyntheticProbeForLab(clock.get()))
            assertThat(phone.acceptMxH1SyntheticProbeForLab(f6)).isTrue()
            tvRecords = checkNotNull(originalTv.claimSourceRecordsAfterSixthWritten(
                clock::get, { true },
            ))
            phoneRecords = checkNotNull(phone.claimSourceRecordsAfterVerifiedTvEof(
                clock::get, { true },
            ))
            listOf(offer, f2, f3, f4, f5, f6).forEach { it.fill(0) }
            owner = Lc04TvOriginalAeadCommandOwner.fromOriginalTvRecordsAfterRealF6(
                originalTv = originalTv,
                sourceRecords = checkNotNull(tvRecords),
                staging = ProtectedSourceStaging(onboarding),
                clock = clock::get,
                selectedRouteEligible = { true },
                isTvVisible = { true },
            )
        }

        suspend fun send(command: Lc04SourceCommand): Lc04SourceReply? {
            val records = checkNotNull(phoneRecords)
            val request = records.sealRequest(command) ?: return null
            return try {
                val encryptedReply = owner?.acceptAuthenticatedRecord(request) ?: return null
                try { records.openReply(encryptedReply) }
                finally { encryptedReply.fill(0) }
            } finally { request.fill(0) }
        }

        override fun close() {
            owner?.close()
            phoneRecords?.close()
            tvRecords?.close()
            phone.close()
            phoneSigner.close()
            originalTv.close()
            credentials.erase()
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun encryptedM3uRequiresTvDpadBeforeRealDurableActivation() = fixture { f ->
        f.beginOriginalTvF6()
        val secret = "https://provider.example/tv.m3u?token=EPHEMERAL_SECRET"
        val prepared = checkNotNull(f.send(Lc04SourceCommand.PrepareM3u(1, secret)))
        assertThat(prepared.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(prepared.toString()).doesNotContain("EPHEMERAL_SECRET")
        assertThat(f.credentials.count).isEqualTo(1)
        assertThat(f.pending.rows).hasSize(1)
        assertThat(f.pending.rows.values.single().toString()).doesNotContain("EPHEMERAL_SECRET")
        assertThat(f.refreshes).isEmpty()
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()

        assertThat(f.send(Lc04SourceCommand.Activate(2, "Домашний IPTV"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.refreshes).isEmpty()
        assertThat(f.owner?.pendingActivationNameForTv()).isEqualTo("Домашний IPTV")
        assertThat(f.owner?.approvePendingActivationOnTv()).isTrue()
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()
        val result = checkNotNull(f.send(Lc04SourceCommand.Activate(3, "Домашний IPTV")))
        assertThat(result.code).isEqualTo(Lc04SourceReplyCode.ACTIVATED)
        assertThat(result.toString()).doesNotContain("EPHEMERAL_SECRET")
        assertThat(f.refreshes).hasSize(1)
        assertThat(f.refreshes.single().sourceName).isEqualTo("Домашний IPTV")
        assertThat(f.refreshes.single().sourceId).startsWith("source-")
        assertThat(f.pending.rows).isEmpty() // durable preparation consumed
        assertThat(f.credentials.count).isEqualTo(1) // active source keeps access
    }

    @Test fun encryptedM3uCancelRemovesDurablePreparationWithoutRefresh() = fixture { f ->
        f.beginOriginalTvF6()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(
            1, "https://provider.example/list.m3u?token=CANCEL_SECRET",
        ))?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(f.credentials.count).isEqualTo(1)
        assertThat(f.pending.rows).hasSize(1)

        assertThat(f.send(Lc04SourceCommand.Cancel(2))?.code)
            .isEqualTo(Lc04SourceReplyCode.CANCELED)
        assertThat(f.credentials.count).isEqualTo(0)
        assertThat(f.pending.rows).isEmpty()
        assertThat(f.refreshes).isEmpty()
        assertThat(f.owner?.pendingActivationNameForTv()).isNull()
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()
    }

    @Test fun encryptedHttpWithoutTvLocalApprovalCannotPrepareCredential() = fixture { f ->
        f.beginOriginalTvF6()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(
            1, "http://provider.example/list.m3u?token=UNSAFE_SECRET",
        ))?.code).isEqualTo(Lc04SourceReplyCode.TV_HTTP_APPROVAL_REQUIRED)
        assertThat(f.credentials.count).isEqualTo(0)
        assertThat(f.pending.rows).isEmpty()
        assertThat(f.refreshes).isEmpty()
    }
}
