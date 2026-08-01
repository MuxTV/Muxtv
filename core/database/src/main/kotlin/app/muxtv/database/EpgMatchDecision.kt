package app.muxtv.database

internal enum class EpgMatchReasonCode {
    EXACT_ID,
    EXACT_TVG_NAME,
    EXACT_RAW_NAME,
    NO_MATCH,
}

internal sealed interface EpgMatchResolution {
    val reasonCode: EpgMatchReasonCode

    data class Matched(
        val canonicalChannelId: String,
        override val reasonCode: EpgMatchReasonCode,
    ) : EpgMatchResolution {
        init {
            require(canonicalChannelId.isNotBlank())
            require(reasonCode != EpgMatchReasonCode.NO_MATCH)
        }

        override fun toString(): String =
            "EpgMatchResolution.Matched(reasonCode=$reasonCode)"
    }

    data class Ambiguous(
        override val reasonCode: EpgMatchReasonCode,
        val candidateCount: Int,
    ) : EpgMatchResolution {
        init {
            require(reasonCode != EpgMatchReasonCode.NO_MATCH)
            require(candidateCount >= 2)
        }
    }

    data class Unresolved(
        override val reasonCode: EpgMatchReasonCode,
    ) : EpgMatchResolution {
        init {
            require(reasonCode == EpgMatchReasonCode.NO_MATCH)
        }
    }
}

internal fun collapseEpgMatchCandidates(
    canonicalChannelIds: Iterable<String>,
    reasonCode: EpgMatchReasonCode,
): EpgMatchResolution? {
    require(reasonCode != EpgMatchReasonCode.NO_MATCH)
    val distinctCanonicalIds = linkedSetOf<String>()
    canonicalChannelIds.forEach { canonicalChannelId ->
        require(canonicalChannelId.isNotBlank())
        distinctCanonicalIds += canonicalChannelId
    }

    return when (distinctCanonicalIds.size) {
        0 -> null
        1 -> EpgMatchResolution.Matched(
            canonicalChannelId = distinctCanonicalIds.first(),
            reasonCode = reasonCode,
        )
        else -> EpgMatchResolution.Ambiguous(
            reasonCode = reasonCode,
            candidateCount = distinctCanonicalIds.size,
        )
    }
}

internal fun unresolvedEpgMatch(): EpgMatchResolution.Unresolved =
    EpgMatchResolution.Unresolved(EpgMatchReasonCode.NO_MATCH)
