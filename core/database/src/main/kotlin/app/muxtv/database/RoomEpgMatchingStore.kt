package app.muxtv.database

import kotlinx.coroutines.CancellationException

interface EpgMatchingStore {
    suspend fun reconcile(epgSourceId: String): EpgMatchingReconcileResult

    suspend fun reconcileIfStale(epgSourceId: String): EpgMatchingReconcileResult

    suspend fun reconcileAllIfStale()

    suspend fun reconcileProviderSource(
        providerSourceId: String,
    ): EpgProviderMatchingReconcileResult
}

data class EpgMatchingSummary(
    val epgRevisionNumber: Long,
    val catalogRevisionNumber: Long,
    val matchedCount: Int,
    val ambiguousCount: Int,
    val unresolvedCount: Int,
) {
    init {
        require(epgRevisionNumber > 0)
        require(catalogRevisionNumber > 0)
        require(matchedCount >= 0)
        require(ambiguousCount >= 0)
        require(unresolvedCount >= 0)
    }
}

sealed interface EpgMatchingReconcileResult {
    data class Applied(
        val summary: EpgMatchingSummary,
    ) : EpgMatchingReconcileResult

    data object Current : EpgMatchingReconcileResult
    data object NotReady : EpgMatchingReconcileResult
    data object Superseded : EpgMatchingReconcileResult
}

sealed interface EpgProviderMatchingReconcileResult {
    data class Applied(
        val processedCount: Int,
        val appliedCount: Int,
        val notReadyCount: Int,
        val supersededCount: Int,
    ) : EpgProviderMatchingReconcileResult {
        init {
            require(processedCount >= 0)
            require(appliedCount >= 0)
            require(notReadyCount >= 0)
            require(supersededCount >= 0)
            require(appliedCount + notReadyCount + supersededCount == processedCount)
        }
    }
}

internal class RoomEpgMatchingStore(
    private val dao: EpgMatchingDao,
) : EpgMatchingStore {
    override suspend fun reconcile(epgSourceId: String): EpgMatchingReconcileResult {
        require(epgSourceId.isNotBlank())
        val snapshot = dao.relationSnapshot(epgSourceId)
            ?: return EpgMatchingReconcileResult.NotReady
        return reconcileSnapshot(snapshot)
    }

    override suspend fun reconcileIfStale(epgSourceId: String): EpgMatchingReconcileResult {
        require(epgSourceId.isNotBlank())
        val snapshot = dao.relationSnapshot(epgSourceId)
            ?: return EpgMatchingReconcileResult.NotReady
        if (dao.isFresh(snapshot, CURRENT_EPG_MATCH_POLICY_VERSION)) {
            return EpgMatchingReconcileResult.Current
        }
        return reconcileSnapshot(snapshot)
    }

    override suspend fun reconcileAllIfStale() {
        dao.activeLinkedEpgSourceIds().forEach { epgSourceId ->
            try {
                reconcileIfStale(epgSourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup repair is best effort per source. Refresh publication can retry derived state later.
            }
        }
    }

    private suspend fun reconcileSnapshot(
        snapshot: EpgMatchingRelationSnapshot,
    ): EpgMatchingReconcileResult {
        val channels = dao.epgChannels(
            epgSourceId = snapshot.epgSourceId,
            epgRevisionNumber = snapshot.epgRevisionNumber,
        )

        val resolutions = LinkedHashMap<String, EpgMatchResolution>(channels.size)

        run {
            val index = buildEvidenceIndex(
                rows = dao.providerIdEvidence(
                    providerSourceId = snapshot.providerSourceId,
                    catalogRevisionNumber = snapshot.catalogRevisionNumber,
                ),
                normalize = ::normalizeEpgProviderId,
            )
            channels.forEach { channel ->
                val key = normalizeEpgProviderId(channel.externalId) ?: return@forEach
                index.resolve(key, EpgMatchReasonCode.EXACT_ID)
                    ?.let { resolution -> resolutions[channel.externalId] = resolution }
            }
        }

        run {
            val index = buildEvidenceIndex(
                rows = dao.providerTvgNameEvidence(
                    providerSourceId = snapshot.providerSourceId,
                    catalogRevisionNumber = snapshot.catalogRevisionNumber,
                ),
                normalize = ::normalizeEpgDisplayName,
            )
            channels.forEach { channel ->
                if (channel.externalId in resolutions) return@forEach
                val key = normalizeEpgDisplayName(channel.primaryDisplayName) ?: return@forEach
                index.resolve(key, EpgMatchReasonCode.EXACT_TVG_NAME)
                    ?.let { resolution -> resolutions[channel.externalId] = resolution }
            }
        }

        run {
            val index = buildEvidenceIndex(
                rows = dao.providerRawNameEvidence(
                    providerSourceId = snapshot.providerSourceId,
                    catalogRevisionNumber = snapshot.catalogRevisionNumber,
                ),
                normalize = ::normalizeEpgDisplayName,
            )
            channels.forEach { channel ->
                if (channel.externalId in resolutions) return@forEach
                val key = normalizeEpgDisplayName(channel.primaryDisplayName) ?: return@forEach
                index.resolve(key, EpgMatchReasonCode.EXACT_RAW_NAME)
                    ?.let { resolution -> resolutions[channel.externalId] = resolution }
            }
        }

        val matches = ArrayList<EpgChannelMatchEntity>(channels.size)
        var matchedCount = 0
        var ambiguousCount = 0
        var unresolvedCount = 0
        channels.forEach { channel ->
            val match = resolutionToEntity(
                snapshot = snapshot,
                externalId = channel.externalId,
                resolution = resolutions[channel.externalId] ?: unresolvedEpgMatch(),
            )
            when (EpgChannelMatchDecision.valueOf(match.decision)) {
                EpgChannelMatchDecision.MATCHED -> matchedCount++
                EpgChannelMatchDecision.AMBIGUOUS -> ambiguousCount++
                EpgChannelMatchDecision.UNRESOLVED -> unresolvedCount++
            }
            matches += match
        }
        val summary = EpgMatchingSummary(
            epgRevisionNumber = snapshot.epgRevisionNumber,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            matchedCount = matchedCount,
            ambiguousCount = ambiguousCount,
            unresolvedCount = unresolvedCount,
        )

        return when (dao.replaceIfCurrent(snapshot, matches)) {
            EpgMatchPublicationResult.Applied -> EpgMatchingReconcileResult.Applied(summary)
            EpgMatchPublicationResult.Superseded -> EpgMatchingReconcileResult.Superseded
        }
    }

    override suspend fun reconcileProviderSource(
        providerSourceId: String,
    ): EpgProviderMatchingReconcileResult {
        require(providerSourceId.isNotBlank())
        val linkedSourceIds = dao.linkedActiveEpgSourceIds(providerSourceId)

        var appliedCount = 0
        var notReadyCount = 0
        var supersededCount = 0
        linkedSourceIds.forEach { epgSourceId ->
            when (reconcile(epgSourceId)) {
                is EpgMatchingReconcileResult.Applied -> appliedCount++
                EpgMatchingReconcileResult.Current -> error("Forced provider reconcile cannot be current")
                EpgMatchingReconcileResult.NotReady -> notReadyCount++
                EpgMatchingReconcileResult.Superseded -> supersededCount++
            }
        }
        return EpgProviderMatchingReconcileResult.Applied(
            processedCount = linkedSourceIds.size,
            appliedCount = appliedCount,
            notReadyCount = notReadyCount,
            supersededCount = supersededCount,
        )
    }

    private fun buildEvidenceIndex(
        rows: List<EpgMatchEvidenceRow>,
        normalize: (String?) -> String?,
    ): EpgMatchCandidateIndex {
        val index = EpgMatchCandidateIndex()
        rows.forEach { row ->
            val key = normalize(row.providerValue) ?: return@forEach
            index.add(key, row.canonicalChannelId)
        }
        return index
    }

    private fun resolutionToEntity(
        snapshot: EpgMatchingRelationSnapshot,
        externalId: String,
        resolution: EpgMatchResolution,
    ): EpgChannelMatchEntity = when (resolution) {
        is EpgMatchResolution.Matched -> EpgChannelMatchEntity(
            epgSourceId = snapshot.epgSourceId,
            epgRevisionNumber = snapshot.epgRevisionNumber,
            providerSourceId = snapshot.providerSourceId,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            epgExternalChannelId = externalId,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
            decision = EpgChannelMatchDecision.MATCHED.name,
            reasonCode = resolution.reasonCode.name,
            canonicalChannelId = resolution.canonicalChannelId,
            candidateCount = 1,
        )

        is EpgMatchResolution.Ambiguous -> EpgChannelMatchEntity(
            epgSourceId = snapshot.epgSourceId,
            epgRevisionNumber = snapshot.epgRevisionNumber,
            providerSourceId = snapshot.providerSourceId,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            epgExternalChannelId = externalId,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
            decision = EpgChannelMatchDecision.AMBIGUOUS.name,
            reasonCode = resolution.reasonCode.name,
            canonicalChannelId = null,
            candidateCount = resolution.candidateCount,
        )

        is EpgMatchResolution.Unresolved -> EpgChannelMatchEntity(
            epgSourceId = snapshot.epgSourceId,
            epgRevisionNumber = snapshot.epgRevisionNumber,
            providerSourceId = snapshot.providerSourceId,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            epgExternalChannelId = externalId,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
            decision = EpgChannelMatchDecision.UNRESOLVED.name,
            reasonCode = resolution.reasonCode.name,
            canonicalChannelId = null,
            candidateCount = 0,
        )
    }
}

private class EpgMatchCandidateIndex {
    private val uniqueCanonicalIdByKey = LinkedHashMap<String, String>()
    private val collisionsByKey = LinkedHashMap<String, MutableSet<String>>()

    fun add(key: String, canonicalChannelId: String) {
        require(key.isNotBlank())
        require(canonicalChannelId.isNotBlank())

        val collision = collisionsByKey[key]
        if (collision != null) {
            collision += canonicalChannelId
            return
        }

        val existing = uniqueCanonicalIdByKey[key]
        when {
            existing == null -> uniqueCanonicalIdByKey[key] = canonicalChannelId
            existing == canonicalChannelId -> Unit
            else -> {
                uniqueCanonicalIdByKey.remove(key)
                collisionsByKey[key] = linkedSetOf(existing, canonicalChannelId)
            }
        }
    }

    fun resolve(
        key: String,
        reasonCode: EpgMatchReasonCode,
    ): EpgMatchResolution? {
        require(reasonCode != EpgMatchReasonCode.NO_MATCH)

        collisionsByKey[key]?.let { canonicalChannelIds ->
            return EpgMatchResolution.Ambiguous(
                reasonCode = reasonCode,
                candidateCount = canonicalChannelIds.size,
            )
        }
        return uniqueCanonicalIdByKey[key]?.let { canonicalChannelId ->
            EpgMatchResolution.Matched(
                canonicalChannelId = canonicalChannelId,
                reasonCode = reasonCode,
            )
        }
    }
}
