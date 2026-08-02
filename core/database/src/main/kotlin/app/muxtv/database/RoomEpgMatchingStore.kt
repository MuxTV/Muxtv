package app.muxtv.database

interface EpgMatchingStore {
    suspend fun reconcile(epgSourceId: String): EpgMatchingReconcileResult

    suspend fun reconcileIfStale(epgSourceId: String): EpgMatchingReconcileResult

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
        val currentCount: Int = 0,
        val notReadyCount: Int,
        val supersededCount: Int,
    ) : EpgProviderMatchingReconcileResult {
        init {
            require(processedCount >= 0)
            require(appliedCount >= 0)
            require(currentCount >= 0)
            require(notReadyCount >= 0)
            require(supersededCount >= 0)
            require(appliedCount + currentCount + notReadyCount + supersededCount == processedCount)
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
                collapseEpgMatchCandidates(
                    canonicalChannelIds = index[key].orEmpty(),
                    reasonCode = EpgMatchReasonCode.EXACT_ID,
                )?.let { resolution -> resolutions[channel.externalId] = resolution }
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
                collapseEpgMatchCandidates(
                    canonicalChannelIds = index[key].orEmpty(),
                    reasonCode = EpgMatchReasonCode.EXACT_TVG_NAME,
                )?.let { resolution -> resolutions[channel.externalId] = resolution }
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
                collapseEpgMatchCandidates(
                    canonicalChannelIds = index[key].orEmpty(),
                    reasonCode = EpgMatchReasonCode.EXACT_RAW_NAME,
                )?.let { resolution -> resolutions[channel.externalId] = resolution }
            }
        }

        val matches = channels.map { channel ->
            resolutionToEntity(
                snapshot = snapshot,
                externalId = channel.externalId,
                resolution = resolutions[channel.externalId] ?: unresolvedEpgMatch(),
            )
        }
        val summary = EpgMatchingSummary(
            epgRevisionNumber = snapshot.epgRevisionNumber,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            matchedCount = matches.count { it.decision == EpgChannelMatchDecision.MATCHED.name },
            ambiguousCount = matches.count { it.decision == EpgChannelMatchDecision.AMBIGUOUS.name },
            unresolvedCount = matches.count { it.decision == EpgChannelMatchDecision.UNRESOLVED.name },
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
        var currentCount = 0
        var notReadyCount = 0
        var supersededCount = 0
        linkedSourceIds.forEach { epgSourceId ->
            when (reconcileIfStale(epgSourceId)) {
                is EpgMatchingReconcileResult.Applied -> appliedCount++
                EpgMatchingReconcileResult.Current -> currentCount++
                EpgMatchingReconcileResult.NotReady -> notReadyCount++
                EpgMatchingReconcileResult.Superseded -> supersededCount++
            }
        }
        return EpgProviderMatchingReconcileResult.Applied(
            processedCount = linkedSourceIds.size,
            appliedCount = appliedCount,
            currentCount = currentCount,
            notReadyCount = notReadyCount,
            supersededCount = supersededCount,
        )
    }

    private fun buildEvidenceIndex(
        rows: List<EpgMatchEvidenceRow>,
        normalize: (String?) -> String?,
    ): Map<String, Set<String>> {
        val index = LinkedHashMap<String, MutableSet<String>>()
        rows.forEach { row ->
            val key = normalize(row.providerValue) ?: return@forEach
            index.getOrPut(key) { linkedSetOf() } += row.canonicalChannelId
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
