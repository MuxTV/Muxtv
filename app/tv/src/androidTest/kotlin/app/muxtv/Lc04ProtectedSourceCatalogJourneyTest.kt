package app.muxtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.importer.CatalogImportRequest
import app.muxtv.catalog.importer.CatalogImportResult
import app.muxtv.catalog.importer.CatalogRevisionImporterFactory
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.DefaultRemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceAccessManager
import app.muxtv.catalog.refresh.RemoteSourceActivationCleanup
import app.muxtv.catalog.refresh.RemoteSourceActivator
import app.muxtv.catalog.refresh.RemoteSourceMetadataCleanupResult
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.DatabaseDefaults
import app.muxtv.database.MuxTvDatabaseFactory
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
import app.muxtv.sources.AppSourceOnboarding
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S2J3E4 TV PRODUCT device gate: ORIGINAL QR/F6-authenticated source command
 * reaches actual app SourceOnboarding, CatalogRevisionImporter and Room
 * PlaybackCatalog; two channels are visible ONLY after TV-local D-pad consent.
 * Deterministic playlist is injected at RemoteSourceActivator TEST seam;
 * there is no external server, phone↔TV interoperability or Media3 claim.
 * Real Android two-TCP handshake is separately tested by phone API26/API36.
 */
@RunWith(AndroidJUnit4::class)
class Lc04ProtectedSourceCatalogJourneyTest {
    private class Credentials : CredentialStore {
        private val stored = linkedMapOf<CredentialId, ByteArray>()
        val recordCount: Int get() = stored.size
        override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
            stored.remove(id)?.fill(0)
            stored[id] = secret.copyBytes()
            return CredentialWriteResult.Stored
        }
        override suspend fun read(id: CredentialId): CredentialReadResult =
            stored[id]?.let { CredentialReadResult.Found(SecretBytes.copyOf(it)) }
                ?: CredentialReadResult.NotFound
        override suspend fun remove(id: CredentialId): CredentialRemoveResult {
            val bytes = stored.remove(id) ?: return CredentialRemoveResult.NotFound
            bytes.fill(0)
            return CredentialRemoveResult.Removed
        }
        override suspend fun reset(): CredentialResetResult {
            erase()
            return CredentialResetResult.Reset
        }
        fun erase() {
            stored.values.forEach { it.fill(0) }
            stored.clear()
        }
    }

    @Test fun encryptedM3uTvDpadActivationImportsTwoRealRoomCatalogChannels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Unique marker prevents prior TV emulator catalog data from satisfying the test.
        val marker = "LC04Room" + System.nanoTime()
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="lc04.test.a" group-title="Демонстрация",$marker News
            https://media.invalid/news.m3u8?token=LOCAL_TEST_ONLY
            #EXTINF:-1 tvg-id="lc04.test.b" group-title="Демонстрация",$marker Movies
            https://media.invalid/movies.m3u8?token=LOCAL_TEST_ONLY
        """.trimIndent() + "\n"
        // An isolated database prevents this importer from changing the app's Home state
        // for subsequent MainActivity/D-pad tests in the same instrumentation package.
        val databaseName = "lc04-protected-source-${UUID.randomUUID()}.db"
        val db = MuxTvDatabaseFactory.create(context, databaseName = databaseName)
        val importer = CatalogRevisionImporterFactory.create(db.sourceRevisionStore)
        val credentials = Credentials()
        val sourceUrl = "https://provider.invalid/list.m3u?token=TV_TEST_ONLY"
        val clock = AtomicLong(10_000L)
        val tv = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(clock.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        var tvPort: Lc04SourceAeadPort? = null
        var phonePort: Lc04SourceAeadPort? = null
        var owner: Lc04TvOriginalAeadCommandOwner? = null
        try {
            db.initializer.initialize()
            val onboarding = AppSourceOnboarding(
                DurableRemoteSourceOnboarding(
                    delegate = DefaultRemoteSourceOnboarding(
                        accessManager = RemoteSourceAccessManager(credentials),
                        activator = RemoteSourceActivator { request ->
                            val imported = importer.import(
                                CatalogImportRequest(
                                    sourceId = request.sourceId,
                                    sourceName = request.sourceName,
                                    credentialRef = request.accessCredentialId.value,
                                ),
                                ByteArrayInputStream(m3u.toByteArray(Charsets.UTF_8)),
                            )
                            check(imported is CatalogImportResult.Imported) {
                                "Actual Room importer must succeed before source activation."
                            }
                            RemoteSourceRefreshResult.Refreshed(
                                revisionNumber = imported.revisionNumber,
                                previousRevisionNumber = imported.previousRevisionNumber,
                                entryCount = imported.entryCount,
                                skippedEntries = imported.skippedEntries,
                                warningCount = imported.warningCount,
                            )
                        },
                        activationCleanup = RemoteSourceActivationCleanup { _, _ ->
                            RemoteSourceMetadataCleanupResult.NotFound
                        },
                    ),
                    registry = db.pendingSourcePreparationStore,
                ),
            )
            val gate = checkNotNull(tv.phoneEnrollmentGate(clock::get))
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, clock.get())).isTrue()
            val f1 = checkNotNull(phone.createSignedSeedOffer())
            val f2 = checkNotNull(tv.acceptSyntheticMxH1SeedOffer(f1, clock.get()))
            val f3 = checkNotNull(phone.acceptTvConfirmationAwaitingFinished(f2))
            val f4 = checkNotNull(tv.finishSyntheticMxH1ForProbeLab(f3, clock::get))
            assertThat(phone.acceptTvFinishedForProbeLab(f4, clock::get)).isTrue()
            val f5 = checkNotNull(phone.sealMxH1SyntheticProbeForLab())
            assertThat(tv.acceptMxH1SyntheticProbeForLab(f5, clock.get())).isTrue()
            val f6 = checkNotNull(tv.sealMxH1SyntheticProbeForLab(clock.get()))
            assertThat(phone.acceptMxH1SyntheticProbeForLab(f6)).isTrue()
            tvPort = checkNotNull(tv.claimSourceRecordsAfterSixthWritten(clock::get, { true }))
            phonePort = checkNotNull(phone.claimSourceRecordsAfterVerifiedTvEof(clock::get, { true }))
            listOf(f1, f2, f3, f4, f5, f6).forEach { it.fill(0) }
            owner = Lc04TvOriginalAeadCommandOwner.fromOriginalTvRecordsAfterRealF6(
                originalTv = tv,
                sourceRecords = checkNotNull(tvPort),
                staging = ProtectedSourceStaging(onboarding),
                clock = clock::get,
                selectedRouteEligible = { true },
                isTvVisible = { true },
            )
            val activeOwner = checkNotNull(owner)
            suspend fun send(command: Lc04SourceCommand): Lc04SourceReply? {
                val records = checkNotNull(phonePort)
                val req = records.sealRequest(command) ?: return null
                return try {
                    val reply = owner?.acceptAuthenticatedRecord(req) ?: return null
                    try { records.openReply(reply) } finally { reply.fill(0) }
                } finally { req.fill(0) }
            }

            val query = ChannelQuery(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                searchText = marker,
                limit = 10,
            )
            assertThat(db.playbackCatalog.observeChannels(query).first()).isEmpty()
            assertThat(send(Lc04SourceCommand.PrepareM3u(1, sourceUrl))?.code)
                .isEqualTo(Lc04SourceReplyCode.PREPARED)
            assertThat(db.playbackCatalog.observeChannels(query).first()).isEmpty()
            assertThat(send(Lc04SourceCommand.Activate(2, "Каналы LC04"))?.code)
                .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
            assertThat(db.playbackCatalog.observeChannels(query).first()).isEmpty()
            assertThat(activeOwner.pendingActivationNameForTv()).isEqualTo("Каналы LC04")
            assertThat(activeOwner.approvePendingActivationOnTv()).isTrue()
            assertThat(send(Lc04SourceCommand.Activate(3, "Каналы LC04"))?.code)
                .isEqualTo(Lc04SourceReplyCode.ACTIVATED)
            val channels = withTimeout(10_000) {
                db.playbackCatalog.observeChannels(query).first { it.size == 2 }
            }
            assertThat(channels.map { it.displayName })
                .containsExactly("$marker News", "$marker Movies")
            for (channel in channels) {
                val playable = checkNotNull(db.playbackCatalog.getChannel(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    channelId = channel.channelId,
                ))
                assertThat(playable.variants).hasSize(1)
                assertThat(playable.variants.single().sourceName).isEqualTo("Каналы LC04")
                assertThat(playable.variants.single().locator)
                    .startsWith("https://media.invalid/")
                assertThat(playable.toString()).doesNotContain("TV_TEST_ONLY")
            }
            assertThat(credentials.recordCount).isEqualTo(1)
        } finally {
            try {
                owner?.close()
                phonePort?.close()
                tvPort?.close()
                phone.close()
                signer.close()
                tv.close()
                credentials.erase()
            } finally {
                try {
                    db.close()
                } finally {
                    context.deleteDatabase(databaseName)
                    check(!context.getDatabasePath(databaseName).exists()) {
                        "Isolated LC04 Room database was not deleted"
                    }
                }
            }
        }
    }
}
