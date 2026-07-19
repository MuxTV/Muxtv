package app.muxtv.model

import app.muxtv.common.CanonicalChannelId as CommonCanonicalChannelId
import app.muxtv.common.StreamVariantId as CommonStreamVariantId

typealias CanonicalChannelId = CommonCanonicalChannelId
typealias StreamVariantId = CommonStreamVariantId

data class CanonicalChannel(
    val id: CanonicalChannelId,
    val displayName: String,
) {
    init { require(displayName.isNotBlank()) }
}

data class StreamVariant(
    val id: StreamVariantId,
    val canonicalChannelId: CanonicalChannelId,
    val locator: String,
) {
    init { require(locator.isNotBlank()) }
}
